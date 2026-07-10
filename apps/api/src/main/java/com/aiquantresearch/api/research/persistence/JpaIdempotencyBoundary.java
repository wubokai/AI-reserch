package com.aiquantresearch.api.research.persistence;

import com.aiquantresearch.api.research.application.IdempotencyBoundary;
import com.aiquantresearch.api.research.application.IdempotencyKeyReusedException;
import com.aiquantresearch.api.research.application.IdempotencyRequestInProgressException;
import com.aiquantresearch.api.research.application.IdempotencyReservation;
import com.aiquantresearch.api.research.application.IdempotencyScope;
import com.aiquantresearch.api.research.application.InvalidResearchRequestException;
import com.aiquantresearch.api.research.application.ResearchApplicationException;
import com.aiquantresearch.api.research.domain.IdempotencyStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaIdempotencyBoundary implements IdempotencyBoundary {

    private static final Duration RETENTION = Duration.ofHours(24);

    private final IdempotencyRecordRepository repository;

    public JpaIdempotencyBoundary(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    public IdempotencyReservation reserve(IdempotencyScope scope, Instant now) {
        validateScope(scope);
        UUID recordId = UUID.randomUUID();
        int inserted = repository.reserve(
                recordId,
                scope.ownerId(),
                scope.httpMethod().toUpperCase(Locale.ROOT),
                scope.requestPath(),
                scope.idempotencyKey(),
                scope.requestHash(),
                now.plus(RETENTION),
                now
        );
        if (inserted == 1) {
            return IdempotencyReservation.acquired(recordId);
        }

        IdempotencyRecordEntity existing = repository
                .findByUserIdAndHttpMethodAndRequestPathAndIdempotencyKey(
                        scope.ownerId(),
                        scope.httpMethod().toUpperCase(Locale.ROOT),
                        scope.requestPath(),
                        scope.idempotencyKey()
                )
                .orElseThrow(() -> new ResearchApplicationException(
                        "IDEMPOTENCY_RESERVATION_FAILED",
                        "The idempotency reservation could not be read",
                        true
                ));

        if (!hashesEqual(existing.getRequestHash(), scope.requestHash())) {
            throw new IdempotencyKeyReusedException();
        }
        if (existing.getStatus() != IdempotencyStatus.COMPLETED
                || existing.getResponseStatus() == null
                || existing.getResponseBody() == null) {
            throw new IdempotencyRequestInProgressException();
        }
        return IdempotencyReservation.replay(
                existing.getId(),
                existing.getResponseStatus(),
                existing.getResponseBody(),
                existing.getResourceId()
        );
    }

    @Override
    public void complete(
            UUID recordId,
            int responseStatus,
            String responseBody,
            UUID resourceId,
            Instant now
    ) {
        if (responseStatus < 200 || responseStatus > 599) {
            throw new IllegalArgumentException("responseStatus must be a valid HTTP status");
        }
        int updated = repository.complete(
                Objects.requireNonNull(recordId, "recordId"),
                (short) responseStatus,
                requireText(responseBody, "responseBody"),
                resourceId,
                Objects.requireNonNull(now, "now")
        );
        if (updated != 1) {
            throw new ResearchApplicationException(
                    "IDEMPOTENCY_COMPLETION_FAILED",
                    "The idempotent response could not be committed",
                    true
            );
        }
    }

    private static void validateScope(IdempotencyScope scope) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(scope.ownerId(), "ownerId");
        requireText(scope.requestPath(), "requestPath");
        requireText(scope.requestHash(), "requestHash");
        String method = requireText(scope.httpMethod(), "httpMethod");
        if (!"POST".equalsIgnoreCase(method)) {
            throw new InvalidResearchRequestException("Idempotency is only defined for POST operations");
        }
        String key = requireText(scope.idempotencyKey(), "Idempotency-Key");
        if (key.length() > 128 || key.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw new InvalidResearchRequestException(
                    "Idempotency-Key must contain 1-128 printable ASCII characters without spaces"
            );
        }
    }

    private static boolean hashesEqual(String first, String second) {
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidResearchRequestException(field + " must not be blank");
        }
        return value;
    }
}
