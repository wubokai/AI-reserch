package com.aiquantresearch.api.research.persistence;

import com.aiquantresearch.api.research.domain.AttemptStatus;
import com.aiquantresearch.api.research.domain.InvalidDomainStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Table(name = "step_attempts")
public class StepAttemptEntity {

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    @Id
    private UUID id;

    @Column(name = "research_step_id", nullable = false, updatable = false)
    private UUID researchStepId;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private AttemptStatus status;

    @Column(name = "retryable", nullable = false)
    private boolean retryable;

    @Column(name = "input_hash", nullable = false, updatable = false, length = 64)
    private String inputHash;

    @Column(name = "output_hash", length = 64)
    private String outputHash;

    @Column(name = "worker_id", nullable = false, updatable = false, length = 128)
    private String workerId;

    @Column(name = "lease_token", nullable = false, updatable = false)
    private UUID leaseToken;

    @Column(name = "lease_expires_at", nullable = false)
    private Instant leaseExpiresAt;

    @Column(name = "heartbeat_at", nullable = false)
    private Instant heartbeatAt;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_code", length = 128)
    private String errorCode;

    @Column(name = "error_message_safe", length = 2000)
    private String errorMessageSafe;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StepAttemptEntity() {
        // Required by JPA.
    }

    public static StepAttemptEntity start(
            UUID id,
            UUID researchStepId,
            int attemptNumber,
            String inputHash,
            String workerId,
            UUID leaseToken,
            Instant leaseExpiresAt,
            Instant now
    ) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be positive");
        }
        if (leaseExpiresAt == null || !leaseExpiresAt.isAfter(now)) {
            throw new IllegalArgumentException("leaseExpiresAt must be after now");
        }
        var entity = new StepAttemptEntity();
        entity.id = Objects.requireNonNull(id, "id");
        entity.researchStepId = Objects.requireNonNull(researchStepId, "researchStepId");
        entity.attemptNumber = attemptNumber;
        entity.status = AttemptStatus.RUNNING;
        entity.inputHash = requireHash(inputHash, "inputHash");
        entity.workerId = requireText(workerId, "workerId");
        entity.leaseToken = Objects.requireNonNull(leaseToken, "leaseToken");
        entity.leaseExpiresAt = leaseExpiresAt;
        entity.heartbeatAt = Objects.requireNonNull(now, "now");
        entity.startedAt = now;
        entity.createdAt = now;
        return entity;
    }

    public void heartbeat(UUID token, Instant nextExpiry, Instant now) {
        requireCurrentLease(token, now);
        if (nextExpiry == null || !nextExpiry.isAfter(now)) {
            throw new IllegalArgumentException("nextExpiry must be after now");
        }
        heartbeatAt = now;
        leaseExpiresAt = nextExpiry;
    }

    public void succeed(UUID token, String nextOutputHash, Instant now) {
        if (status == AttemptStatus.SUCCEEDED) {
            if (!leaseToken.equals(token)) {
                throw new InvalidDomainStateException("STALE_LEASE");
            }
            if (Objects.equals(outputHash, nextOutputHash)) {
                return;
            }
            throw new InvalidDomainStateException(
                    "A successful attempt cannot publish a different output"
            );
        }
        requireCurrentLease(token, now);
        status = AttemptStatus.SUCCEEDED;
        outputHash = requireHash(nextOutputHash, "outputHash");
        finish(now);
    }

    public void fail(UUID token, boolean canRetry, String code, String safeMessage, Instant now) {
        requireCurrentLease(token, now);
        status = AttemptStatus.FAILED;
        retryable = canRetry;
        errorCode = requireText(code, "code");
        errorMessageSafe = normalizeNullable(safeMessage);
        finish(now);
    }

    public void cancel(UUID token, Instant now) {
        requireCurrentLease(token, now);
        status = AttemptStatus.CANCELLED;
        retryable = false;
        finish(now);
    }

    public void expire(Instant now) {
        if (status != AttemptStatus.RUNNING || leaseExpiresAt.isAfter(now)) {
            throw new InvalidDomainStateException("Only an expired RUNNING lease can be reaped");
        }
        status = AttemptStatus.LEASE_EXPIRED;
        retryable = true;
        errorCode = "LEASE_EXPIRED";
        errorMessageSafe = "Worker lease expired before the step completed";
        finish(now);
    }

    public boolean hasCurrentLease(UUID token, Instant now) {
        return status == AttemptStatus.RUNNING
                && leaseToken.equals(token)
                && leaseExpiresAt.isAfter(now);
    }

    private void requireCurrentLease(UUID token, Instant now) {
        if (!hasCurrentLease(token, now)) {
            throw new InvalidDomainStateException("STALE_LEASE");
        }
    }

    private void finish(Instant now) {
        completedAt = Objects.requireNonNull(now, "now");
        durationMs = Math.max(0, Duration.between(startedAt, now).toMillis());
    }

    private static String requireHash(String value, String field) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 hex digest");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID getId() {
        return id;
    }

    public UUID getResearchStepId() {
        return researchStepId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String getInputHash() {
        return inputHash;
    }

    public String getOutputHash() {
        return outputHash;
    }

    public String getWorkerId() {
        return workerId;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessageSafe() {
        return errorMessageSafe;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
