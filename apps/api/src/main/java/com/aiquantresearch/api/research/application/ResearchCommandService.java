package com.aiquantresearch.api.research.application;

import com.aiquantresearch.api.research.domain.InvalidDomainStateException;
import com.aiquantresearch.api.research.domain.ResearchStatus;
import com.aiquantresearch.api.research.domain.StepStatus;
import com.aiquantresearch.api.research.domain.StepType;
import com.aiquantresearch.api.research.persistence.ResearchJobEntity;
import com.aiquantresearch.api.research.persistence.ResearchJobRepository;
import com.aiquantresearch.api.research.persistence.ResearchStepEntity;
import com.aiquantresearch.api.research.persistence.ResearchStepRepository;
import com.aiquantresearch.api.shared.config.ApplicationProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResearchCommandService {

    private static final String CREATE_PATH = "/api/v1/research";
    private static final int STEP_PAYLOAD_VERSION = 1;
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int MANUAL_RETRY_BUDGET = 3;
    private static final String ROOT_UPSTREAM_FINGERPRINT = "ROOT:v1";

    private final ResearchJobRepository researchJobRepository;
    private final ResearchStepRepository researchStepRepository;
    private final OwnerProvisioningService ownerProvisioningService;
    private final SecurityPrecheckService securityPrecheckService;
    private final IdempotencyBoundary idempotencyBoundary;
    private final CanonicalHashService hashService;
    private final ResearchEventJournal eventJournal;
    private final ResearchQueryService queryService;
    private final ApplicationProperties applicationProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ResearchCommandService(
            ResearchJobRepository researchJobRepository,
            ResearchStepRepository researchStepRepository,
            OwnerProvisioningService ownerProvisioningService,
            SecurityPrecheckService securityPrecheckService,
            IdempotencyBoundary idempotencyBoundary,
            CanonicalHashService hashService,
            ResearchEventJournal eventJournal,
            ResearchQueryService queryService,
            ApplicationProperties applicationProperties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.researchJobRepository = researchJobRepository;
        this.researchStepRepository = researchStepRepository;
        this.ownerProvisioningService = ownerProvisioningService;
        this.securityPrecheckService = securityPrecheckService;
        this.idempotencyBoundary = idempotencyBoundary;
        this.hashService = hashService;
        this.eventJournal = eventJournal;
        this.queryService = queryService;
        this.applicationProperties = applicationProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public CommandResult<ResearchAcceptedView> create(
            UUID ownerId,
            String principalName,
            String principalEmail,
            String idempotencyKey,
            CreateResearchCommand command
    ) {
        Objects.requireNonNull(command, "command");
        ownerProvisioningService.ensureOwner(ownerId, principalName, principalEmail);
        Instant now = clock.instant();
        String requestJson = hashService.canonicalJson(command);
        String requestHash = hashService.hashCanonicalJsonText(requestJson);
        IdempotencyReservation reservation = idempotencyBoundary.reserve(
                new IdempotencyScope(ownerId, "POST", CREATE_PATH, idempotencyKey, requestHash),
                now
        );
        if (reservation.replayed()) {
            return replay(reservation, ResearchAcceptedView.class);
        }
        securityPrecheckService.validate(command.symbol(), command.companyName());

        UUID researchId = UUID.randomUUID();
        ResearchJobEntity research = ResearchJobEntity.create(
                researchId,
                ownerId,
                command.symbol(),
                command.query(),
                command.locale(),
                requestJson,
                applicationProperties.dataMode(),
                now
        );
        research.queue(now);
        researchJobRepository.save(research);

        List<ResearchStepEntity> steps = new ArrayList<>(StepType.values().length);
        String upstreamFingerprint = ROOT_UPSTREAM_FINGERPRINT;
        for (StepType type : StepType.values()) {
            String implementationVersion = implementationVersion(type);
            String inputHash = stepInputHash(
                    requestHash,
                    type,
                    implementationVersion,
                    upstreamFingerprint
            );
            steps.add(ResearchStepEntity.create(
                    UUID.randomUUID(),
                    researchId,
                    type,
                    inputHash,
                    STEP_PAYLOAD_VERSION,
                    requestJson,
                    implementationVersion,
                    100 - type.sequence(),
                    DEFAULT_MAX_ATTEMPTS,
                    type == StepType.RESOLVE_SECURITY ? now : null,
                    now,
                    ownerId
            ));
            upstreamFingerprint = plannedInputFingerprint(inputHash);
        }
        researchStepRepository.saveAll(steps);
        eventJournal.append(
                researchId,
                ResearchEventJournal.ActorType.USER,
                ownerId,
                "RESEARCH_CREATED",
                "RESEARCH_QUEUED",
                Map.of(
                        "status", research.getStatus().name(),
                        "stepCount", steps.size(),
                        "dataMode", research.getDataMode().name()
                ),
                Map.of(
                        "researchId", researchId.toString(),
                        "status", research.getStatus().name(),
                        "dataMode", research.getDataMode().name()
                ),
                now
        );

        var response = new ResearchAcceptedView(
                researchId,
                research.getStatus(),
                research.getDataMode(),
                research.getCreatedAt()
        );
        complete(reservation, 202, response, researchId, now);
        return new CommandResult<>(response, false);
    }

    @Transactional
    public CommandResult<ResearchAcceptedView> create(
            UUID ownerId,
            String principalEmail,
            String idempotencyKey,
            CreateResearchCommand command
    ) {
        return create(ownerId, principalEmail, principalEmail, idempotencyKey, command);
    }

    @Transactional
    public CommandResult<ResearchAcceptedView> retry(
            UUID ownerId,
            UUID researchId,
            String idempotencyKey,
            RetryResearchCommand command
    ) {
        command = command == null ? RetryResearchCommand.fromFirstFailedStep() : command;
        Instant now = clock.instant();
        String path = researchPath(researchId) + "/retry";
        String requestHash = hashService.hash(command);
        IdempotencyReservation reservation = idempotencyBoundary.reserve(
                new IdempotencyScope(ownerId, "POST", path, idempotencyKey, requestHash),
                now
        );
        if (reservation.replayed()) {
            return replay(reservation, ResearchAcceptedView.class);
        }

        ResearchJobEntity research = findOwnedForUpdate(ownerId, researchId);
        if (!research.getStatus().acceptsManualRetry()) {
            throw new InvalidStateTransitionException(
                    "Research in state " + research.getStatus() + " cannot be retried"
            );
        }
        ResearchStatus retryFromStatus = research.getStatus();
        List<ResearchStepEntity> steps = researchStepRepository
                .findAllByResearchJobIdForUpdate(researchId);
        List<StepRetryPlan> retryPlan = buildRetryPlan(research, steps);
        StepType requestedStart = selectRetryStart(command.fromStep(), retryPlan);

        StepRetryPlan firstRunnablePlan = retryPlan.stream()
                .filter(plan -> plan.step().getSequenceNo() >= requestedStart.sequence())
                .filter(StepRetryPlan::requiresExecution)
                .findFirst()
                .orElseThrow(() -> new InvalidStateTransitionException(
                        "No failed or incomplete step is eligible for retry"
                ));
        if (retryPlan.stream()
                .filter(plan -> plan.step().getSequenceNo() >= requestedStart.sequence())
                .anyMatch(plan -> plan.step().getStatus() == StepStatus.RUNNING)) {
            throw new InvalidStateTransitionException("A running step cannot be manually retried");
        }
        ResearchStepEntity firstRunnable = firstRunnablePlan.step();

        try {
            research.prepareManualRetry(firstRunnable.getStepType(), now, ownerId);
        } catch (InvalidDomainStateException exception) {
            throw new InvalidStateTransitionException(exception.getMessage());
        }
        // The database step guard recognizes manual retry only after the owning job is QUEUED.
        // Flush this state first; all Step entities are still unchanged at this boundary.
        researchJobRepository.saveAndFlush(research);

        List<ResearchStepEntity> changedSteps = new ArrayList<>();
        for (StepRetryPlan plan : retryPlan) {
            ResearchStepEntity step = plan.step();
            if (step.getSequenceNo() < requestedStart.sequence()) {
                continue;
            }
            boolean willRun = step.prepareManualRetry(
                    plan.nextInputHash(),
                    plan.nextImplementationVersion(),
                    MANUAL_RETRY_BUDGET,
                    false,
                    now,
                    ownerId
            );
            if (willRun) {
                changedSteps.add(step);
            }
        }
        firstRunnable.unlock(now, ownerId);
        researchStepRepository.saveAll(steps);
        var retryMetadata = new java.util.LinkedHashMap<String, Object>();
        retryMetadata.put("fromStatus", retryFromStatus.name());
        retryMetadata.put("toStatus", research.getStatus().name());
        retryMetadata.put("requestedFromStep", requestedStart.name());
        retryMetadata.put("firstRunnableStep", firstRunnable.getStepType().name());
        retryMetadata.put("resetStepCount", changedSteps.size());
        retryMetadata.put("reason", SafeReasonNormalizer.withDefault(command.reason()));
        var retryPayload = new java.util.LinkedHashMap<String, Object>();
        retryPayload.put("researchId", researchId.toString());
        retryPayload.put("status", research.getStatus().name());
        retryPayload.put("currentStep", firstRunnable.getStepType().name());
        retryPayload.put("changedSteps", changedStepPayload(changedSteps));
        eventJournal.append(
                researchId,
                ResearchEventJournal.ActorType.USER,
                ownerId,
                "RETRY_REQUESTED",
                "RESEARCH_RETRY_QUEUED",
                retryMetadata,
                retryPayload,
                now
        );

        var response = new ResearchAcceptedView(
                research.getId(),
                research.getStatus(),
                research.getDataMode(),
                research.getCreatedAt()
        );
        complete(reservation, 202, response, researchId, now);
        return new CommandResult<>(response, false);
    }

    @Transactional
    public CommandResult<ResearchStatusView> cancel(
            UUID ownerId,
            UUID researchId,
            String idempotencyKey,
            CancelResearchCommand command
    ) {
        command = command == null ? CancelResearchCommand.withoutReason() : command;
        Instant now = clock.instant();
        String path = researchPath(researchId) + "/cancel";
        IdempotencyReservation reservation = idempotencyBoundary.reserve(
                new IdempotencyScope(ownerId, "POST", path, idempotencyKey, hashService.hash(command)),
                now
        );
        if (reservation.replayed()) {
            return replay(reservation, ResearchStatusView.class);
        }

        ResearchJobEntity research = findOwnedForUpdate(ownerId, researchId);
        if (research.getStatus() == ResearchStatus.COMPLETED
                || research.getStatus() == ResearchStatus.PARTIALLY_COMPLETED
                || research.getStatus() == ResearchStatus.CANCELLED) {
            ResearchStatusView response = queryService.status(ownerId, researchId);
            complete(reservation, 202, response, researchId, now);
            return new CommandResult<>(response, false);
        }
        if (research.getStatus().isTerminal()) {
            throw new InvalidStateTransitionException(
                    "Research in state " + research.getStatus() + " cannot be cancelled"
            );
        }

        boolean alreadyRequested = research.isCancellationRequested();
        try {
            research.requestCancellation(now, ownerId);
        } catch (InvalidDomainStateException exception) {
            throw new InvalidStateTransitionException(exception.getMessage());
        }
        List<ResearchStepEntity> steps = researchStepRepository
                .findAllByResearchJobIdForUpdate(researchId);
        List<ResearchStepEntity> changedSteps = new ArrayList<>();
        steps.forEach(step -> {
            if (step.cancelIfNotSucceeded(now, ownerId)) {
                changedSteps.add(step);
            }
        });
        boolean hasRunningStep = steps.stream()
                .anyMatch(step -> step.getStatus() == StepStatus.RUNNING);
        if (!hasRunningStep) {
            research.confirmCancellation(now, ownerId);
        }
        researchStepRepository.saveAll(steps);
        researchJobRepository.save(research);
        if (!alreadyRequested) {
            var cancelMetadata = new java.util.LinkedHashMap<String, Object>();
            cancelMetadata.put("pendingStepsCancelled", changedSteps.size());
            cancelMetadata.put(
                    "settledImmediately",
                    research.getStatus() == ResearchStatus.CANCELLED
            );
            cancelMetadata.put("reason", SafeReasonNormalizer.withDefault(command.reason()));
            var cancelPayload = new java.util.LinkedHashMap<String, Object>();
            cancelPayload.put("researchId", researchId.toString());
            cancelPayload.put("status", research.getStatus().name());
            cancelPayload.put("cancellationRequested", true);
            cancelPayload.put("changedSteps", changedStepPayload(changedSteps));
            eventJournal.append(
                    researchId,
                    ResearchEventJournal.ActorType.USER,
                    ownerId,
                    "CANCEL_REQUESTED",
                    "RESEARCH_CANCELLATION_REQUESTED",
                    cancelMetadata,
                    cancelPayload,
                    now
            );
        }

        ResearchStatusView response = queryService.status(ownerId, researchId);
        complete(reservation, 202, response, researchId, now);
        return new CommandResult<>(response, false);
    }

    @Transactional
    public void softDelete(UUID ownerId, UUID researchId, String reason) {
        ResearchJobEntity research = findOwnedForDelete(ownerId, researchId);
        if (research.getDeletedAt() != null) {
            return;
        }
        if (!research.getStatus().isTerminal()) {
            throw new InvalidStateTransitionException(
                    "An active research job must be cancelled or completed before deletion"
            );
        }
        Instant now = clock.instant();
        String safeReason = SafeReasonNormalizer.withDefault(reason);
        research.softDelete(now, ownerId, safeReason);
        researchJobRepository.save(research);
        var deleteMetadata = new java.util.LinkedHashMap<String, Object>();
        deleteMetadata.put("status", research.getStatus().name());
        deleteMetadata.put("reason", safeReason);
        eventJournal.append(
                researchId,
                ResearchEventJournal.ActorType.USER,
                ownerId,
                "SOFT_DELETED",
                "RESEARCH_SOFT_DELETED",
                deleteMetadata,
                Map.of("researchId", researchId.toString()),
                now
        );
    }

    @Transactional
    public void softDelete(UUID ownerId, UUID researchId) {
        softDelete(ownerId, researchId, "USER_REQUESTED");
    }

    private StepType selectRetryStart(
            StepType requested,
            List<StepRetryPlan> retryPlan
    ) {
        if (requested != null) {
            boolean exists = retryPlan.stream()
                    .anyMatch(plan -> plan.step().getStepType() == requested);
            if (!exists) {
                throw new InvalidResearchRequestException("fromStep is not part of this research plan");
            }
            boolean skippedRequiredRecomputation = retryPlan.stream()
                    .filter(plan -> plan.step().getSequenceNo() < requested.sequence())
                    .anyMatch(StepRetryPlan::requiresExecution);
            if (skippedRequiredRecomputation) {
                throw new InvalidResearchRequestException(
                        "fromStep cannot skip an incomplete or stale required dependency"
                );
            }
            return requested;
        }
        return retryPlan.stream()
                .filter(StepRetryPlan::requiresExecution)
                .map(plan -> plan.step().getStepType())
                .findFirst()
                .orElseThrow(() -> new InvalidStateTransitionException(
                        "No failed or incomplete step is eligible for retry"
                ));
    }

    private List<StepRetryPlan> buildRetryPlan(
            ResearchJobEntity research,
            List<ResearchStepEntity> steps
    ) {
        String requestHash = hashService.hashCanonicalJsonText(research.getRequestJson());
        String upstreamFingerprint = ROOT_UPSTREAM_FINGERPRINT;
        List<StepRetryPlan> result = new ArrayList<>(steps.size());
        for (ResearchStepEntity step : steps) {
            String nextImplementationVersion = implementationVersion(step.getStepType());
            String nextInputHash = stepInputHash(
                    requestHash,
                    step.getStepType(),
                    nextImplementationVersion,
                    upstreamFingerprint
            );
            boolean reusableSuccess = step.getStatus() == StepStatus.SUCCEEDED
                    && step.getSuccessfulOutputHash() != null
                    && step.getInputHash().equals(nextInputHash)
                    && step.getImplementationVersion().equals(nextImplementationVersion);
            result.add(new StepRetryPlan(
                    step,
                    nextInputHash,
                    nextImplementationVersion,
                    reusableSuccess
            ));
            upstreamFingerprint = reusableSuccess
                    ? successfulOutputFingerprint(step.getSuccessfulOutputHash())
                    : plannedInputFingerprint(nextInputHash);
        }
        return List.copyOf(result);
    }

    private ResearchJobEntity findOwnedForUpdate(UUID ownerId, UUID researchId) {
        return researchJobRepository.findOwnedForUpdate(researchId, ownerId)
                .orElseThrow(() -> new ResearchNotFoundException(researchId));
    }

    private ResearchJobEntity findOwnedForDelete(UUID ownerId, UUID researchId) {
        return researchJobRepository.findOwnedForUpdate(researchId, ownerId)
                // PostgreSQL rechecks the deleted_at predicate after waiting for a row lock.
                // If a concurrent delete won, preserve DELETE idempotency without mutating again.
                .or(() -> researchJobRepository.findByIdAndOwnerId(researchId, ownerId)
                        .filter(existing -> existing.getDeletedAt() != null))
                .orElseThrow(() -> new ResearchNotFoundException(researchId));
    }

    String stepInputHash(
            String requestHash,
            StepType type,
            String implementationVersion,
            String upstreamFingerprint
    ) {
        return hashService.hashText(
                requestHash
                        + "|" + type.name()
                        + "|" + implementationVersion
                        + "|upstream=" + upstreamFingerprint
        );
    }

    static String implementationVersion(StepType type) {
        return "phase2-" + type.name().toLowerCase(java.util.Locale.ROOT) + "-v1";
    }

    private static String plannedInputFingerprint(String inputHash) {
        return "PLANNED_INPUT:" + inputHash;
    }

    static String successfulOutputFingerprint(String outputHash) {
        return "SUCCESSFUL_OUTPUT:" + outputHash;
    }

    private static List<Map<String, Object>> changedStepPayload(
            List<ResearchStepEntity> changedSteps
    ) {
        return changedSteps.stream()
                .map(step -> Map.<String, Object>of(
                        "stepId", step.getId().toString(),
                        "stepType", step.getStepType().name(),
                        "status", step.getStatus().name()
                ))
                .toList();
    }

    private record StepRetryPlan(
            ResearchStepEntity step,
            String nextInputHash,
            String nextImplementationVersion,
            boolean reusableSuccess
    ) {
        boolean requiresExecution() {
            return !reusableSuccess;
        }
    }

    private static String researchPath(UUID researchId) {
        return "/api/v1/research/" + Objects.requireNonNull(researchId, "researchId");
    }

    private void complete(
            IdempotencyReservation reservation,
            int status,
            Object response,
            UUID researchId,
            Instant now
    ) {
        idempotencyBoundary.complete(
                reservation.recordId(),
                status,
                hashService.canonicalJson(response),
                researchId,
                now
        );
    }

    private <T> CommandResult<T> replay(IdempotencyReservation reservation, Class<T> responseType) {
        try {
            T response = objectMapper.readValue(reservation.responseBody(), responseType);
            return new CommandResult<>(response, true);
        } catch (JsonProcessingException exception) {
            throw new ResearchApplicationException(
                    "IDEMPOTENT_RESPONSE_INVALID",
                    "The stored idempotent response is invalid",
                    false
            );
        }
    }
}
