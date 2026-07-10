package com.aiquantresearch.api.research.persistence;

import com.aiquantresearch.api.research.domain.InvalidDomainStateException;
import com.aiquantresearch.api.research.domain.StepStatus;
import com.aiquantresearch.api.research.domain.StepType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "research_steps")
public class ResearchStepEntity {

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    @Id
    private UUID id;

    @Column(name = "research_job_id", nullable = false, updatable = false)
    private UUID researchJobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, updatable = false, length = 40)
    private StepType stepType;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private short sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private StepStatus status;

    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;

    @Column(name = "successful_output_hash", length = 64)
    private String successfulOutputHash;

    @Column(name = "payload_version", nullable = false)
    private int payloadVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson;

    @Column(name = "implementation_version", nullable = false, length = 128)
    private String implementationVersion;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "available_at")
    private Instant availableAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "skip_reason", length = 500)
    private String skipReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected ResearchStepEntity() {
        // Required by JPA.
    }

    public static ResearchStepEntity create(
            UUID id,
            UUID researchJobId,
            StepType stepType,
            String inputHash,
            int payloadVersion,
            String payloadJson,
            String implementationVersion,
            int priority,
            int maxAttempts,
            Instant availableAt,
            Instant now,
            UUID actorId
    ) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        var entity = new ResearchStepEntity();
        entity.id = Objects.requireNonNull(id, "id");
        entity.researchJobId = Objects.requireNonNull(researchJobId, "researchJobId");
        entity.stepType = Objects.requireNonNull(stepType, "stepType");
        entity.sequenceNo = stepType.sequence();
        entity.status = StepStatus.PENDING;
        entity.inputHash = requireHash(inputHash, "inputHash");
        if (payloadVersion < 1) {
            throw new IllegalArgumentException("payloadVersion must be positive");
        }
        entity.payloadVersion = payloadVersion;
        entity.payloadJson = requireText(payloadJson, "payloadJson");
        entity.implementationVersion = requireText(implementationVersion, "implementationVersion");
        entity.priority = priority;
        entity.maxAttempts = maxAttempts;
        entity.availableAt = availableAt;
        entity.createdAt = Objects.requireNonNull(now, "now");
        entity.updatedAt = now;
        entity.createdBy = Objects.requireNonNull(actorId, "actorId");
        entity.updatedBy = actorId;
        return entity;
    }

    public int beginAttempt(Instant now, UUID actorId) {
        if (status != StepStatus.PENDING || availableAt == null || availableAt.isAfter(now)) {
            throw new InvalidDomainStateException("Step is not runnable");
        }
        if (!hasAttemptBudget()) {
            throw new InvalidDomainStateException("Step retry budget is exhausted");
        }
        status = StepStatus.RUNNING;
        availableAt = null;
        attemptCount++;
        touch(now, actorId);
        return attemptCount;
    }

    public void unlock(Instant now, UUID actorId) {
        if (status != StepStatus.PENDING) {
            throw new InvalidDomainStateException("Only pending steps can be unlocked");
        }
        availableAt = Objects.requireNonNull(now, "now");
        touch(now, actorId);
    }

    public void succeed(String outputHash, Instant now, UUID actorId) {
        outputHash = requireHash(outputHash, "outputHash");
        if (status == StepStatus.SUCCEEDED) {
            if (!Objects.equals(successfulOutputHash, outputHash)) {
                throw new InvalidDomainStateException("A successful step cannot publish a different output");
            }
            return;
        }
        requireRunning();
        status = StepStatus.SUCCEEDED;
        successfulOutputHash = outputHash;
        availableAt = null;
        touch(now, actorId);
    }

    public void scheduleRetry(Instant availableAt, Instant now, UUID actorId) {
        requireRunning();
        if (!hasAttemptBudget()) {
            throw new InvalidDomainStateException("Step retry budget is exhausted");
        }
        status = StepStatus.PENDING;
        this.availableAt = Objects.requireNonNull(availableAt, "availableAt");
        touch(now, actorId);
    }

    public void fail(Instant now, UUID actorId) {
        requireRunning();
        status = StepStatus.FAILED;
        availableAt = null;
        touch(now, actorId);
    }

    public boolean cancelIfNotSucceeded(Instant now, UUID actorId) {
        if (status == StepStatus.SUCCEEDED || status == StepStatus.SKIPPED) {
            return false;
        }
        if (status == StepStatus.CANCELLED) {
            return false;
        }
        if (status == StepStatus.PENDING) {
            status = StepStatus.CANCELLED;
            availableAt = null;
            touch(now, actorId);
            return true;
        }
        return false;
    }

    public void confirmRunningCancellation(Instant now, UUID actorId) {
        requireRunning();
        status = StepStatus.CANCELLED;
        availableAt = null;
        touch(now, actorId);
    }

    public void skip(String reason, Instant now, UUID actorId) {
        if (status != StepStatus.PENDING) {
            throw new InvalidDomainStateException("Only pending steps can be skipped");
        }
        status = StepStatus.SKIPPED;
        skipReason = requireText(reason, "reason");
        availableAt = null;
        touch(now, actorId);
    }

    /** Returns true when this step must run in the new execution. */
    public boolean prepareManualRetry(
            String nextInputHash,
            String nextImplementationVersion,
            int additionalAttemptBudget,
            boolean initiallyRunnable,
            Instant now,
            UUID actorId
    ) {
        nextInputHash = requireHash(nextInputHash, "nextInputHash");
        nextImplementationVersion = requireText(nextImplementationVersion, "nextImplementationVersion");
        boolean sameExecutionInput = inputHash.equals(nextInputHash)
                && implementationVersion.equals(nextImplementationVersion);

        if (status == StepStatus.SUCCEEDED && sameExecutionInput) {
            return false;
        }
        if (status == StepStatus.RUNNING) {
            throw new InvalidDomainStateException("A running step cannot be manually retried");
        }
        if (additionalAttemptBudget < 1) {
            throw new IllegalArgumentException("additionalAttemptBudget must be positive");
        }

        inputHash = nextInputHash;
        implementationVersion = nextImplementationVersion;
        successfulOutputHash = null;
        skipReason = null;
        status = StepStatus.PENDING;
        maxAttempts = Math.addExact(Math.max(maxAttempts, attemptCount), additionalAttemptBudget);
        availableAt = initiallyRunnable ? now : null;
        touch(now, actorId);
        return true;
    }

    public boolean hasAttemptBudget() {
        return attemptCount < maxAttempts;
    }

    private void requireRunning() {
        if (status != StepStatus.RUNNING) {
            throw new InvalidDomainStateException("Step must be RUNNING, but was " + status);
        }
    }

    private void touch(Instant now, UUID actorId) {
        updatedAt = Objects.requireNonNull(now, "now");
        updatedBy = Objects.requireNonNull(actorId, "actorId");
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

    public UUID getId() {
        return id;
    }

    public UUID getResearchJobId() {
        return researchJobId;
    }

    public StepType getStepType() {
        return stepType;
    }

    public short getSequenceNo() {
        return sequenceNo;
    }

    public StepStatus getStatus() {
        return status;
    }

    public String getInputHash() {
        return inputHash;
    }

    public String getSuccessfulOutputHash() {
        return successfulOutputHash;
    }

    public int getPayloadVersion() {
        return payloadVersion;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public String getImplementationVersion() {
        return implementationVersion;
    }

    public int getPriority() {
        return priority;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getRowVersion() {
        return rowVersion;
    }
}
