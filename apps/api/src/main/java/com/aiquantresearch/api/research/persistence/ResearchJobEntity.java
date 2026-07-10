package com.aiquantresearch.api.research.persistence;

import com.aiquantresearch.api.research.domain.InvalidDomainStateException;
import com.aiquantresearch.api.research.domain.ResearchLocale;
import com.aiquantresearch.api.research.domain.ResearchStatus;
import com.aiquantresearch.api.research.domain.StepType;
import com.aiquantresearch.api.shared.domain.DataMode;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "research_jobs")
public class ResearchJobEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "security_id")
    private UUID securityId;

    @Column(name = "symbol_input", length = 10)
    private String symbolInput;

    @Column(name = "query", nullable = false, columnDefinition = "text")
    private String query;

    @Column(name = "locale", nullable = false, length = 5)
    private String locale;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_json", nullable = false, columnDefinition = "jsonb")
    private String requestJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private ResearchStatus status;

    @Column(name = "progress", nullable = false)
    private short progress;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", length = 40)
    private StepType currentStep;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_mode", nullable = false, length = 16)
    private DataMode dataMode;

    @Column(name = "cancellation_requested", nullable = false)
    private boolean cancellationRequested;

    @Column(name = "cancellation_requested_at")
    private Instant cancellationRequestedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;

    @Column(name = "delete_reason", length = 500)
    private String deleteReason;

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

    protected ResearchJobEntity() {
        // Required by JPA.
    }

    public static ResearchJobEntity create(
            UUID id,
            UUID ownerId,
            String symbolInput,
            String query,
            ResearchLocale locale,
            String requestJson,
            DataMode dataMode,
            Instant now
    ) {
        var entity = new ResearchJobEntity();
        entity.id = Objects.requireNonNull(id, "id");
        entity.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        entity.symbolInput = normalizeNullable(symbolInput);
        entity.query = requireText(query, "query");
        entity.locale = Objects.requireNonNull(locale, "locale").value();
        entity.requestJson = requireText(requestJson, "requestJson");
        entity.dataMode = Objects.requireNonNull(dataMode, "dataMode");
        entity.status = ResearchStatus.CREATED;
        entity.progress = 0;
        entity.cancellationRequested = false;
        entity.createdAt = Objects.requireNonNull(now, "now");
        entity.updatedAt = now;
        entity.createdBy = ownerId;
        entity.updatedBy = ownerId;
        return entity;
    }

    public void queue(Instant now) {
        transitionTo(ResearchStatus.QUEUED, 0, StepType.RESOLVE_SECURITY, now, updatedBy);
    }

    public void transitionTo(
            ResearchStatus target,
            int targetProgress,
            StepType targetStep,
            Instant now,
            UUID actorId
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(now, "now");
        validateProgress(targetProgress);

        if (status == target) {
            if (targetProgress < progress) {
                throw new InvalidDomainStateException("Research progress cannot move backwards");
            }
            progress = (short) targetProgress;
            currentStep = target.isTerminal() ? null : targetStep;
            touch(now, actorId);
            return;
        }
        boolean transitionAllowed = status == ResearchStatus.QUEUED && !target.isTerminal()
                ? isQueuedCheckpointProjection(target, targetProgress, targetStep)
                : status.canTransitionTo(target);
        if (!transitionAllowed) {
            throw new InvalidDomainStateException(
                    "Illegal research transition from " + status + " to " + target
            );
        }
        if (targetProgress < progress) {
            throw new InvalidDomainStateException("Research progress cannot move backwards");
        }
        if (cancellationRequested && target != ResearchStatus.CANCELLED) {
            throw new InvalidDomainStateException(
                    "A cancelled research job cannot publish a success or failure transition"
            );
        }
        if (target == ResearchStatus.CANCELLED && !cancellationRequested) {
            throw new InvalidDomainStateException("Cancellation must be requested before confirmation");
        }
        if (!target.isTerminal() && target != ResearchStatus.QUEUED && targetStep == null) {
            throw new InvalidDomainStateException("An active research stage requires a current step");
        }

        status = target;
        progress = (short) targetProgress;
        currentStep = target.isTerminal() ? null : targetStep;
        if (startedAt == null && target != ResearchStatus.QUEUED && target != ResearchStatus.CREATED) {
            startedAt = now;
        }
        if (target.isTerminal()) {
            completedAt = now;
            if (target == ResearchStatus.COMPLETED || target == ResearchStatus.PARTIALLY_COMPLETED) {
                progress = 100;
            }
        }
        touch(now, actorId);
    }

    /**
     * A manual retry stores the first runnable durable step while the public job is QUEUED.
     * It may resume directly at that exact checkpoint, but cannot use QUEUED as a shortcut
     * to project any other workflow stage.
     */
    private boolean isQueuedCheckpointProjection(
            ResearchStatus target,
            int targetProgress,
            StepType targetStep
    ) {
        return status == ResearchStatus.QUEUED
                && targetStep != null
                && currentStep == targetStep
                && target == targetStep.publicStatus()
                && progress == queuedCheckpointProgress(targetStep)
                && targetProgress == targetStep.progress();
    }

    public void requestCancellation(Instant now, UUID actorId) {
        Objects.requireNonNull(now, "now");
        if (status.isTerminal()) {
            throw new InvalidDomainStateException("Cannot cancel terminal research in state " + status);
        }
        if (!cancellationRequested) {
            cancellationRequested = true;
            cancellationRequestedAt = now;
            touch(now, actorId);
        }
    }

    public void confirmCancellation(Instant now, UUID actorId) {
        transitionTo(ResearchStatus.CANCELLED, progress, null, now, actorId);
    }

    /**
     * Opens a new execution after an explicit user retry while preserving all attempt history.
     * Ordinary workflow transitions cannot leave terminal states; only this operation may do so.
     */
    public void prepareManualRetry(StepType firstRunnableStep, Instant now, UUID actorId) {
        Objects.requireNonNull(firstRunnableStep, "firstRunnableStep");
        if (!status.acceptsManualRetry()) {
            throw new InvalidDomainStateException("Research in state " + status + " cannot be retried");
        }
        status = ResearchStatus.QUEUED;
        progress = queuedCheckpointProgress(firstRunnableStep);
        currentStep = firstRunnableStep;
        cancellationRequested = false;
        cancellationRequestedAt = null;
        completedAt = null;
        startedAt = null;
        touch(now, actorId);
    }

    private static short queuedCheckpointProgress(StepType step) {
        return step == StepType.RESOLVE_SECURITY
                ? 0
                : (short) (step.progress() - 1);
    }

    public void resolveSecurity(UUID resolvedSecurityId, Instant now, UUID actorId) {
        securityId = Objects.requireNonNull(resolvedSecurityId, "resolvedSecurityId");
        touch(now, actorId);
    }

    public void softDelete(Instant now, UUID actorId, String reason) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(actorId, "actorId");
        if (deletedAt == null) {
            deletedAt = now;
            deletedBy = actorId;
            deleteReason = normalizeNullable(reason);
            if (deleteReason == null) {
                deleteReason = "USER_REQUESTED";
            }
            touch(now, actorId);
        }
    }

    private void touch(Instant now, UUID actorId) {
        updatedAt = Objects.requireNonNull(now, "now");
        updatedBy = Objects.requireNonNullElse(actorId, ownerId);
    }

    private static void validateProgress(int value) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("progress must be between 0 and 100");
        }
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

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getSecurityId() {
        return securityId;
    }

    public String getSymbolInput() {
        return symbolInput;
    }

    public String getQuery() {
        return query;
    }

    public ResearchLocale getLocale() {
        return ResearchLocale.fromValue(locale);
    }

    public String getRequestJson() {
        return requestJson;
    }

    public ResearchStatus getStatus() {
        return status;
    }

    public int getProgress() {
        return progress;
    }

    public StepType getCurrentStep() {
        return currentStep;
    }

    public DataMode getDataMode() {
        return dataMode;
    }

    public boolean isCancellationRequested() {
        return cancellationRequested;
    }

    public Instant getCancellationRequestedAt() {
        return cancellationRequestedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public UUID getDeletedBy() {
        return deletedBy;
    }

    public String getDeleteReason() {
        return deleteReason;
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
