package com.aiquantresearch.api.research.application;

import com.aiquantresearch.api.research.domain.InvalidDomainStateException;
import com.aiquantresearch.api.research.domain.ResearchStatus;
import com.aiquantresearch.api.research.domain.StepStatus;
import com.aiquantresearch.api.research.domain.StepType;
import com.aiquantresearch.api.research.persistence.ResearchJobEntity;
import com.aiquantresearch.api.research.persistence.ResearchJobRepository;
import com.aiquantresearch.api.research.persistence.ResearchStepEntity;
import com.aiquantresearch.api.research.persistence.ResearchStepRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal Java finalizer/stage projection boundary.
 *
 * <p>Workers may complete durable steps, but only this service is allowed to
 * project a public stage or adjudicate the Research terminal status.
 */
@Service
public class ResearchWorkflowService {

    private static final String UPSTREAM_DEPENDENCY_UNSATISFIED =
            "UPSTREAM_DEPENDENCY_UNSATISFIED";

    private static final List<StepStatus> ACTIVE_STEP_STATES = List.of(
            StepStatus.PENDING,
            StepStatus.RUNNING
    );

    private final ResearchJobRepository researchJobRepository;
    private final ResearchStepRepository researchStepRepository;
    private final ResearchQueryService queryService;
    private final ResearchEventJournal eventJournal;
    private final Clock clock;

    public ResearchWorkflowService(
            ResearchJobRepository researchJobRepository,
            ResearchStepRepository researchStepRepository,
            ResearchQueryService queryService,
            ResearchEventJournal eventJournal,
            Clock clock
    ) {
        this.researchJobRepository = researchJobRepository;
        this.researchStepRepository = researchStepRepository;
        this.queryService = queryService;
        this.eventJournal = eventJournal;
        this.clock = clock;
    }

    @Transactional
    public ResearchStatusView projectStage(UUID researchId, StepType currentStep) {
        ResearchJobEntity research = findForUpdate(researchId);
        ResearchStatus previousStatus = research.getStatus();
        if (research.getStatus() == currentStep.publicStatus()
                && research.getProgress() >= currentStep.progress()
                && research.getCurrentStep() == currentStep) {
            return queryService.status(research.getOwnerId(), researchId);
        }
        if (research.isCancellationRequested()) {
            throw new InvalidStateTransitionException(
                    "A cancellation-requested research job cannot enter a new stage"
            );
        }
        List<StepType> skippedProjectionStages = validateSkippedProjection(
                research,
                currentStep
        );
        var now = clock.instant();
        try {
            // The database keeps the rank+1 guard as a defense-in-depth invariant.
            // Persist intermediate SKIPPED stages inside this transaction; no observer
            // can see them, and the public event records only the claimed runnable stage.
            for (StepType skippedStage : skippedProjectionStages) {
                research.transitionTo(
                        skippedStage.publicStatus(),
                        skippedStage.progress(),
                        skippedStage,
                        now,
                        research.getOwnerId()
                );
                researchJobRepository.saveAndFlush(research);
            }
            research.transitionTo(
                    currentStep.publicStatus(),
                    Math.max(research.getProgress(), currentStep.progress()),
                    currentStep,
                    now,
                    research.getOwnerId()
            );
        } catch (InvalidDomainStateException exception) {
            throw new InvalidStateTransitionException(exception.getMessage());
        }
        researchJobRepository.save(research);
        appendStatusChange(
                research,
                previousStatus,
                "RESEARCH_STAGE_CHANGED",
                currentStep,
                List.of()
        );
        return queryService.status(research.getOwnerId(), researchId);
    }

    /**
     * Returns the persisted plan stages that may be crossed by this projection.
     * Ordinary adjacent and QUEUED-checkpoint projections remain governed by the
     * entity/database transition guards. A wider jump is legal only when the old
     * runnable step succeeded, every intervening durable step is SKIPPED, and the
     * target step is the currently claimed RUNNING step.
     */
    private List<StepType> validateSkippedProjection(
            ResearchJobEntity research,
            StepType targetStep
    ) {
        StepType projectedStep = research.getCurrentStep();
        if (research.getStatus() == ResearchStatus.QUEUED
                || projectedStep == null
                || targetStep.sequence() <= projectedStep.sequence() + 1) {
            return List.of();
        }

        List<ResearchStepEntity> persistedPlan = researchStepRepository
                .findAllByResearchJobIdForUpdate(research.getId());
        ResearchStepEntity previousRunnable = findPlanStep(persistedPlan, projectedStep);
        ResearchStepEntity target = findPlanStep(persistedPlan, targetStep);
        if (previousRunnable.getStatus() != StepStatus.SUCCEEDED
                || target.getStatus() != StepStatus.RUNNING) {
            throw illegalSkippedProjection(projectedStep, targetStep);
        }

        var skippedStages = new java.util.ArrayList<StepType>();
        for (int sequence = projectedStep.sequence() + 1;
             sequence < targetStep.sequence();
             sequence++) {
            StepType skippedType = StepType.atSequence(sequence);
            ResearchStepEntity skippedStep = findPlanStep(persistedPlan, skippedType);
            if (skippedStep.getStatus() != StepStatus.SKIPPED) {
                throw illegalSkippedProjection(projectedStep, targetStep);
            }
            skippedStages.add(skippedType);
        }
        return List.copyOf(skippedStages);
    }

    private static ResearchStepEntity findPlanStep(
            List<ResearchStepEntity> persistedPlan,
            StepType stepType
    ) {
        return persistedPlan.stream()
                .filter(step -> step.getStepType() == stepType)
                .findFirst()
                .orElseThrow(() -> new InvalidStateTransitionException(
                        "Persisted research plan is missing step " + stepType
                ));
    }

    private static InvalidStateTransitionException illegalSkippedProjection(
            StepType projectedStep,
            StepType targetStep
    ) {
        return new InvalidStateTransitionException(
                "Illegal durable stage projection from " + projectedStep
                        + " to " + targetStep
                        + ": only persisted SKIPPED stages may be crossed and the target must be RUNNING"
        );
    }

    @Transactional
    public ResearchStatusView finalizeResearch(
            UUID researchId,
            boolean fullDeliveryPolicyPassed,
            boolean minimumSafePolicyPassed
    ) {
        ResearchJobEntity research = findForUpdate(researchId);
        ResearchStatus previousStatus = research.getStatus();
        if (research.getStatus().isTerminal()) {
            return queryService.status(research.getOwnerId(), researchId);
        }
        if (!research.isCancellationRequested()
                && (fullDeliveryPolicyPassed || minimumSafePolicyPassed)) {
            throw new InvalidStateTransitionException(
                    "Successful finalization requires an atomically published and "
                            + "validated report through the report publication boundary"
            );
        }
        var steps = researchStepRepository.findAllByResearchJobIdForUpdate(researchId);
        boolean activeSteps;
        List<ResearchStepEntity> changedSteps = List.of();
        if (research.isCancellationRequested()) {
            // Cancellation owns its own convergence path and must leave PENDING steps as
            // CANCELLED, never rewrite them to dependency SKIPPED.
            activeSteps = steps.stream().anyMatch(step -> ACTIVE_STEP_STATES.contains(
                    step.getStatus()
            ));
        } else {
            StepSettlement settlement = classifyStepsForFinalization(steps);
            activeSteps = settlement.active();
            if (!activeSteps && !settlement.blocked().isEmpty()) {
                var now = clock.instant();
                settlement.blocked().forEach(step -> step.skip(
                        UPSTREAM_DEPENDENCY_UNSATISFIED,
                        now,
                        research.getOwnerId()
                ));
                researchStepRepository.saveAll(settlement.blocked());
                changedSteps = settlement.blocked();
            }
        }
        if (activeSteps) {
            throw new InvalidStateTransitionException(
                    "Research cannot be finalized while runnable or running steps remain"
            );
        }

        ResearchStatus target;
        if (research.isCancellationRequested()) {
            target = ResearchStatus.CANCELLED;
        } else {
            target = ResearchStatus.FAILED;
        }

        try {
            research.transitionTo(
                    target,
                    target == ResearchStatus.COMPLETED
                            || target == ResearchStatus.PARTIALLY_COMPLETED
                            ? 100
                            : research.getProgress(),
                    null,
                    clock.instant(),
                    research.getOwnerId()
            );
        } catch (InvalidDomainStateException exception) {
            throw new InvalidStateTransitionException(exception.getMessage());
        }
        researchJobRepository.save(research);
        appendStatusChange(
                research,
                previousStatus,
                "RESEARCH_FINALIZED",
                null,
                changedSteps
        );
        return queryService.status(research.getOwnerId(), researchId);
    }

    @Transactional
    public boolean confirmCancellationIfSettled(UUID researchId) {
        ResearchJobEntity research = findForUpdate(researchId);
        if (!research.isCancellationRequested() || research.getStatus().isTerminal()) {
            return false;
        }
        boolean activeSteps = researchStepRepository.existsByResearchJobIdAndStatusIn(
                researchId,
                ACTIVE_STEP_STATES
        );
        if (activeSteps) {
            return false;
        }
        try {
            ResearchStatus previousStatus = research.getStatus();
            research.confirmCancellation(clock.instant(), research.getOwnerId());
            researchJobRepository.save(research);
            appendStatusChange(
                    research,
                    previousStatus,
                    "RESEARCH_CANCELLED",
                    null,
                    List.of()
            );
        } catch (InvalidDomainStateException exception) {
            throw new InvalidStateTransitionException(exception.getMessage());
        }
        return true;
    }

    private ResearchJobEntity findForUpdate(UUID researchId) {
        return researchJobRepository.findActiveForUpdate(researchId)
                .orElseThrow(() -> new ResearchNotFoundException(researchId));
    }

    /**
     * Classifies a linear plan without confusing dependency-blocked rows with runnable work.
     * A non-null availableAt is authoritative runnable/retry work. A null availableAt row is
     * active only when every predecessor already satisfies its dependency; otherwise it is
     * permanently blocked only when some predecessor FAILED or was CANCELLED.
     */
    private static StepSettlement classifyStepsForFinalization(
            List<ResearchStepEntity> steps
    ) {
        boolean active = false;
        boolean allPredecessorsSatisfied = true;
        boolean failedOrCancelledPredecessor = false;
        var blocked = new java.util.ArrayList<ResearchStepEntity>();

        for (var step : steps) {
            switch (step.getStatus()) {
                case RUNNING -> {
                    active = true;
                    allPredecessorsSatisfied = false;
                }
                case PENDING -> {
                    if (step.getAvailableAt() != null) {
                        active = true;
                    } else if (failedOrCancelledPredecessor) {
                        blocked.add(step);
                    } else if (allPredecessorsSatisfied) {
                        // Dependency is satisfied but the unlock transaction has not run yet.
                        active = true;
                    }
                    allPredecessorsSatisfied = false;
                }
                case FAILED, CANCELLED -> {
                    failedOrCancelledPredecessor = true;
                    allPredecessorsSatisfied = false;
                }
                case SUCCEEDED, SKIPPED -> {
                    // These states satisfy their own dependency. A prior unsatisfied state,
                    // if any, deliberately remains visible to every later linear step.
                }
            }
        }
        return new StepSettlement(active, List.copyOf(blocked));
    }

    private record StepSettlement(
            boolean active,
            List<ResearchStepEntity> blocked
    ) {
    }

    private void appendStatusChange(
            ResearchJobEntity research,
            ResearchStatus previousStatus,
            String eventType,
            StepType currentStep,
            List<ResearchStepEntity> changedSteps
    ) {
        var metadata = new java.util.LinkedHashMap<String, Object>();
        metadata.put("fromStatus", previousStatus.name());
        metadata.put("toStatus", research.getStatus().name());
        metadata.put("progress", research.getProgress());
        if (currentStep != null) {
            metadata.put("currentStep", currentStep.name());
        }
        if (!changedSteps.isEmpty()) {
            metadata.put("changedStepCount", changedSteps.size());
        }
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("researchId", research.getId().toString());
        payload.put("status", research.getStatus().name());
        payload.put("progress", research.getProgress());
        if (!changedSteps.isEmpty()) {
            payload.put("changedSteps", changedStepPayload(changedSteps));
        }
        eventJournal.append(
                research.getId(),
                ResearchEventJournal.ActorType.SYSTEM,
                null,
                "STATUS_CHANGED",
                eventType,
                metadata,
                payload,
                clock.instant()
        );
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
}
