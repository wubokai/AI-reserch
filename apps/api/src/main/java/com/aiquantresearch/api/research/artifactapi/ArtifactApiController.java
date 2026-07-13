package com.aiquantresearch.api.research.artifactapi;

import com.aiquantresearch.api.research.application.AuthenticatedOwnerService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1")
public class ArtifactApiController {

    private static final String CLAIM_TYPE = "FACT|CALCULATION|INFERENCE|OPINION";
    private static final String SOURCE_TYPE = "EXCHANGE|MARKET_DATA_PROVIDER|COMPANY_FILING|"
            + "SEC_FILING|GOVERNMENT_DATA|COMPANY_WEBSITE|NEWS|INTERNAL_CALCULATION|MOCK|OTHER";
    private static final String FRESHNESS = "FRESH|STALE|VERY_STALE|UNKNOWN";
    private static final String SECURITY_TYPE = "COMMON_STOCK|ETF";

    private final ArtifactQueryService queryService;
    private final ResearchInsightsService insightsService;
    private final AuthenticatedOwnerService authenticatedOwnerService;
    private final ReportExportService exportService;

    public ArtifactApiController(
            ArtifactQueryService queryService,
            ResearchInsightsService insightsService,
            AuthenticatedOwnerService authenticatedOwnerService,
            ReportExportService exportService
    ) {
        this.queryService = queryService;
        this.insightsService = insightsService;
        this.authenticatedOwnerService = authenticatedOwnerService;
        this.exportService = exportService;
    }

    @GetMapping("/research/{researchId}/insights")
    public ResponseEntity<ResearchInsightsResponse> insights(
            @PathVariable UUID researchId,
            @RequestParam @Min(1) int reportVersion,
            Authentication authentication
    ) {
        UUID ownerId = authenticatedOwnerService.requireActive(authentication).id();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(insightsService.insights(ownerId, researchId, reportVersion));
    }

    @GetMapping("/securities/search")
    public ArtifactApiResponses.SecuritySearchResponse searchSecurities(
            @RequestParam("q") @NotBlank @Size(max = 100) String query,
            @RequestParam(required = false) @Pattern(regexp = SECURITY_TYPE) String securityType,
            @RequestParam(required = false) @Size(max = 20) String exchange,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit,
            Authentication authentication
    ) {
        authenticatedOwnerService.requireActive(authentication);
        return queryService.searchSecurities(query, securityType, exchange, limit);
    }

    @GetMapping("/providers/status")
    public ArtifactApiResponses.ProviderStatusResponse providerStatus(Authentication authentication) {
        authenticatedOwnerService.requireActive(authentication);
        return queryService.providerStatus();
    }

    @GetMapping("/research/{researchId}/evidence")
    public ArtifactApiResponses.EvidencePage evidence(
            @PathVariable UUID researchId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Pattern(regexp = CLAIM_TYPE) String claimType,
            @RequestParam(required = false) @Pattern(regexp = SOURCE_TYPE) String sourceType,
            @RequestParam(required = false) @Pattern(regexp = FRESHNESS) String freshnessStatus,
            @RequestParam(required = false) Boolean isDemoData,
            Authentication authentication
    ) {
        UUID ownerId = authenticatedOwnerService.requireActive(authentication).id();
        return queryService.evidence(
                ownerId,
                researchId,
                page,
                size,
                claimType,
                sourceType,
                freshnessStatus,
                isDemoData
        );
    }

    @GetMapping("/research/{researchId}/evidence/search")
    public ArtifactApiResponses.EvidenceSearchResponse searchEvidence(
            @PathVariable UUID researchId,
            @RequestParam("q") @NotBlank @Size(max = 200) String query,
            @RequestParam(defaultValue = "10") @Min(1) @Max(25) int limit,
            Authentication authentication
    ) {
        UUID ownerId = authenticatedOwnerService.requireActive(authentication).id();
        return queryService.searchEvidence(ownerId, researchId, query.strip(), limit);
    }

    @GetMapping("/research/{researchId}/reports")
    public ArtifactApiResponses.ReportVersionPage reports(
            @PathVariable UUID researchId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication authentication
    ) {
        UUID ownerId = authenticatedOwnerService.requireActive(authentication).id();
        return queryService.reports(ownerId, researchId, page, size);
    }

    @GetMapping("/research/{researchId}/reports/{version}")
    public ResponseEntity<ArtifactApiResponses.ReportVersionResponse> report(
            @PathVariable UUID researchId,
            @PathVariable @Min(1) int version,
            Authentication authentication
    ) {
        UUID ownerId = authenticatedOwnerService.requireActive(authentication).id();
        ArtifactApiResponses.ReportVersionResponse response = queryService.report(
                ownerId,
                researchId,
                version
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache().cachePrivate())
                .eTag(response.contentHash())
                .body(response);
    }

    @GetMapping("/research/{researchId}/export")
    public ResponseEntity<byte[]> export(
            @PathVariable UUID researchId,
            @RequestParam(defaultValue = "PDF")
            @Pattern(regexp = "(?i)MARKDOWN|HTML|PDF") String format,
            @RequestParam(required = false) @Min(1) Integer reportVersion,
            Authentication authentication
    ) {
        UUID ownerId = authenticatedOwnerService.requireActive(authentication).id();
        ReportExportArtifact artifact = exportService.export(
                ownerId,
                researchId,
                ReportExportFormat.fromApiValue(format),
                reportVersion
        );
        return ResponseEntity.ok()
                .contentType(artifact.format().mediaType())
                .contentLength(artifact.content().length)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .eTag(artifact.contentHash())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + artifact.filename() + "\""
                )
                .header("X-Report-Version", Integer.toString(artifact.reportVersion()))
                .header("X-Data-Mode", artifact.dataMode().name())
                .header("X-Content-SHA256", artifact.contentHash())
                .body(artifact.content());
    }
}
