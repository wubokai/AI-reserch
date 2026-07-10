package com.aiquantresearch.api.research.web;

import com.aiquantresearch.api.research.application.AuthenticatedOwnerService;
import com.aiquantresearch.api.research.application.CommandResult;
import com.aiquantresearch.api.research.application.ResearchAcceptedView;
import com.aiquantresearch.api.research.application.ResearchCommandService;
import com.aiquantresearch.api.research.application.ResearchListQuery;
import com.aiquantresearch.api.research.application.ResearchQueryService;
import com.aiquantresearch.api.research.application.ResearchSort;
import com.aiquantresearch.api.research.application.ResearchStatusView;
import com.aiquantresearch.api.research.domain.ResearchStatus;
import com.aiquantresearch.api.shared.security.CurrentUserIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/research")
public class ResearchController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String IDEMPOTENCY_REPLAYED = "Idempotency-Replayed";
    private static final String POLL_DELAY_SECONDS = "2";
    private static final String TICKER_PATTERN = "^[A-Za-z][A-Za-z0-9.-]{0,9}$";
    private static final String IDEMPOTENCY_KEY_PATTERN = "^[\\x21-\\x7E]{1,128}$";

    private final ResearchCommandService commandService;
    private final ResearchQueryService queryService;
    private final AuthenticatedOwnerService authenticatedOwnerService;

    public ResearchController(
            ResearchCommandService commandService,
            ResearchQueryService queryService,
            AuthenticatedOwnerService authenticatedOwnerService
    ) {
        this.commandService = commandService;
        this.queryService = queryService;
        this.authenticatedOwnerService = authenticatedOwnerService;
    }

    @PostMapping
    public ResponseEntity<ResearchAcceptedResponse> create(
            @RequestHeader(IDEMPOTENCY_KEY)
            @Pattern(
                    regexp = IDEMPOTENCY_KEY_PATTERN,
                    message = "must contain 1-128 printable ASCII characters without spaces"
            )
            String idempotencyKey,
            @Valid @RequestBody CreateResearchRequest request,
            Authentication authentication
    ) {
        CurrentUserIdentity owner = authenticatedOwnerService.requireActive(authentication);
        CommandResult<ResearchAcceptedView> result = commandService.create(
                owner.id(),
                owner.username(),
                owner.email(),
                idempotencyKey,
                request.toCommand()
        );
        ResearchAcceptedResponse response = ResearchAcceptedResponse.from(result.value());
        return accepted(response, detailUri(response.researchId()), result.idempotencyReplayed());
    }

    @GetMapping
    public ResearchPageResponse list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Pattern(regexp = TICKER_PATTERN) String symbol,
            @RequestParam(required = false) ResearchStatus status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant to,
            @RequestParam(name = "q", required = false) @Size(min = 1, max = 200) String query,
            @RequestParam(defaultValue = "createdAt,desc") String sort,
            Authentication authentication
    ) {
        UUID ownerId = authenticatedOwnerService.requireActive(authentication).id();
        ResearchListQuery listQuery = new ResearchListQuery(
                page,
                size,
                symbol,
                status,
                from,
                to,
                query,
                ResearchSort.fromApiValue(sort)
        );
        return ResearchPageResponse.from(queryService.list(ownerId, listQuery));
    }

    @GetMapping("/{researchId}")
    public ResearchDetailResponse detail(
            @PathVariable UUID researchId,
            Authentication authentication
    ) {
        UUID ownerId = authenticatedOwnerService.requireActive(authentication).id();
        return ResearchDetailResponse.from(queryService.detail(ownerId, researchId));
    }

    @GetMapping("/{researchId}/status")
    public ResearchStatusResponse status(
            @PathVariable UUID researchId,
            Authentication authentication
    ) {
        UUID ownerId = authenticatedOwnerService.requireActive(authentication).id();
        return ResearchStatusResponse.from(queryService.status(ownerId, researchId));
    }

    @PostMapping("/{researchId}/retry")
    public ResponseEntity<ResearchAcceptedResponse> retry(
            @PathVariable UUID researchId,
            @RequestHeader(IDEMPOTENCY_KEY)
            @Pattern(
                    regexp = IDEMPOTENCY_KEY_PATTERN,
                    message = "must contain 1-128 printable ASCII characters without spaces"
            )
            String idempotencyKey,
            @Valid @RequestBody(required = false) RetryResearchRequest request,
            Authentication authentication
    ) {
        UUID ownerId = authenticatedOwnerService.requireActive(authentication).id();
        CommandResult<ResearchAcceptedView> result = commandService.retry(
                ownerId,
                researchId,
                idempotencyKey,
                request == null ? null : request.toCommand()
        );
        ResearchAcceptedResponse response = ResearchAcceptedResponse.from(result.value());
        return accepted(response, statusUri(researchId), result.idempotencyReplayed());
    }

    @PostMapping("/{researchId}/cancel")
    public ResponseEntity<ResearchStatusResponse> cancel(
            @PathVariable UUID researchId,
            @RequestHeader(IDEMPOTENCY_KEY)
            @Pattern(
                    regexp = IDEMPOTENCY_KEY_PATTERN,
                    message = "must contain 1-128 printable ASCII characters without spaces"
            )
            String idempotencyKey,
            @Valid @RequestBody(required = false) CancelResearchRequest request,
            Authentication authentication
    ) {
        UUID ownerId = authenticatedOwnerService.requireActive(authentication).id();
        CommandResult<ResearchStatusView> result = commandService.cancel(
                ownerId,
                researchId,
                idempotencyKey,
                request == null ? null : request.toCommand()
        );
        return accepted(
                ResearchStatusResponse.from(result.value()),
                statusUri(researchId),
                result.idempotencyReplayed()
        );
    }

    @DeleteMapping("/{researchId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID researchId,
            Authentication authentication
    ) {
        UUID ownerId = authenticatedOwnerService.requireActive(authentication).id();
        commandService.softDelete(ownerId, researchId);
        return ResponseEntity.noContent().build();
    }

    private static <T> ResponseEntity<T> accepted(
            T body,
            URI location,
            boolean idempotencyReplayed
    ) {
        return ResponseEntity.accepted()
                .location(location)
                .header(HttpHeaders.RETRY_AFTER, POLL_DELAY_SECONDS)
                .header(IDEMPOTENCY_REPLAYED, Boolean.toString(idempotencyReplayed))
                .body(body);
    }

    private static URI detailUri(UUID researchId) {
        return URI.create("/api/v1/research/" + researchId);
    }

    private static URI statusUri(UUID researchId) {
        return URI.create("/api/v1/research/" + researchId + "/status");
    }
}
