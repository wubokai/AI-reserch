package com.aiquantresearch.api.research.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aiquantresearch.api.research.domain.ResearchLocale;
import com.aiquantresearch.api.research.domain.ResearchStatus;
import com.aiquantresearch.api.research.domain.StepStatus;
import com.aiquantresearch.api.research.domain.StepType;
import com.aiquantresearch.api.research.persistence.ResearchJobEntity;
import com.aiquantresearch.api.research.persistence.ResearchJobRepository;
import com.aiquantresearch.api.research.persistence.ResearchStepEntity;
import com.aiquantresearch.api.research.persistence.ResearchStepRepository;
import com.aiquantresearch.api.shared.domain.DataMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResearchWorkflowServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-10T12:00:00Z");
    private static final Instant FINALIZED_AT = Instant.parse("2026-07-10T12:01:00Z");
    private static final String INPUT_HASH = "a".repeat(64);
    private static final String OUTPUT_HASH = "b".repeat(64);

    @Mock
    private ResearchJobRepository researchJobRepository;

    @Mock
    private ResearchStepRepository researchStepRepository;

    @Mock
    private ResearchQueryService queryService;

    @Mock
    private ResearchEventJournal eventJournal;

    private ResearchWorkflowService service;
    private UUID ownerId;
    private UUID researchId;
    private ResearchJobEntity research;

    @BeforeEach
    void setUp() {
        ownerId = UUID.randomUUID();
        researchId = UUID.randomUUID();
        research = ResearchJobEntity.create(
                researchId,
                ownerId,
                "MU",
                "Analyze durable workflow finalization",
                ResearchLocale.EN_US,
                "{\"query\":\"Analyze durable workflow finalization\",\"symbol\":\"MU\"}",
                DataMode.MOCK,
                CREATED_AT
        );
        research.queue(CREATED_AT.plusSeconds(1));
        service = new ResearchWorkflowService(
                researchJobRepository,
                researchStepRepository,
                queryService,
                eventJournal,
                Clock.fixed(FINALIZED_AT, ZoneOffset.UTC)
        );
        when(researchJobRepository.findActiveForUpdate(researchId))
                .thenReturn(Optional.of(research));
    }

    @Test
    void manualRetryProjectsOnlyItsStoredQueuedCheckpoint() {
        research.transitionTo(
                ResearchStatus.FAILED,
                0,
                null,
                CREATED_AT.plusSeconds(2),
                ownerId
        );
        research.prepareManualRetry(
                StepType.FETCH_MARKET_DATA,
                CREATED_AT.plusSeconds(3),
                ownerId
        );

        assertThatThrownBy(() -> service.projectStage(researchId, StepType.RESOLVE_SECURITY))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Illegal research transition");
        assertThatThrownBy(() -> service.projectStage(researchId, StepType.FETCH_FILINGS))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Illegal research transition");
        assertThat(research.getStatus()).isEqualTo(ResearchStatus.QUEUED);
        assertThat(research.getCurrentStep()).isEqualTo(StepType.FETCH_MARKET_DATA);
        assertThat(research.getProgress())
                .isEqualTo(StepType.FETCH_MARKET_DATA.progress() - 1);

        service.projectStage(researchId, StepType.FETCH_MARKET_DATA);

        assertThat(research.getStatus()).isEqualTo(ResearchStatus.FETCHING_MARKET_DATA);
        assertThat(research.getCurrentStep()).isEqualTo(StepType.FETCH_MARKET_DATA);
        assertThat(research.getProgress()).isEqualTo(StepType.FETCH_MARKET_DATA.progress());
        verify(researchJobRepository).save(research);
        verify(eventJournal).append(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void projectionCrossesOnlyPersistedSkippedStagesToTheClaimedTarget() {
        movePublicProjectionToFilings();
        ResearchStepEntity filings = succeededStep(StepType.FETCH_FILINGS);
        ResearchStepEntity skippedMacro = step(StepType.FETCH_MACRO_DATA, null);
        skippedMacro.skip(
                "ANALYSIS_MODULE_NOT_REQUESTED",
                CREATED_AT.plusSeconds(4),
                ownerId
        );
        ResearchStepEntity claimedValidation = step(StepType.VALIDATE_DATA, CREATED_AT);
        claimedValidation.beginAttempt(CREATED_AT.plusSeconds(5), ownerId);
        when(researchStepRepository.findAllByResearchJobIdForUpdate(researchId))
                .thenReturn(List.of(filings, skippedMacro, claimedValidation));

        service.projectStage(researchId, StepType.VALIDATE_DATA);

        assertThat(research.getStatus()).isEqualTo(ResearchStatus.VALIDATING_DATA);
        assertThat(research.getCurrentStep()).isEqualTo(StepType.VALIDATE_DATA);
        assertThat(research.getProgress()).isEqualTo(StepType.VALIDATE_DATA.progress());
        verify(researchJobRepository).saveAndFlush(research);
        verify(researchJobRepository).save(research);
        verify(eventJournal).append(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void projectionRejectsJumpAcrossAnyNonSkippedPersistedStage() {
        movePublicProjectionToFilings();
        ResearchStepEntity filings = succeededStep(StepType.FETCH_FILINGS);
        ResearchStepEntity pendingMacro = step(StepType.FETCH_MACRO_DATA, null);
        ResearchStepEntity claimedValidation = step(StepType.VALIDATE_DATA, CREATED_AT);
        claimedValidation.beginAttempt(CREATED_AT.plusSeconds(5), ownerId);
        when(researchStepRepository.findAllByResearchJobIdForUpdate(researchId))
                .thenReturn(List.of(filings, pendingMacro, claimedValidation));

        assertThatThrownBy(() -> service.projectStage(researchId, StepType.VALIDATE_DATA))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("only persisted SKIPPED stages may be crossed");

        assertThat(research.getStatus()).isEqualTo(ResearchStatus.FETCHING_FILINGS);
        assertThat(research.getCurrentStep()).isEqualTo(StepType.FETCH_FILINGS);
        verify(researchJobRepository, never()).saveAndFlush(any());
        verify(researchJobRepository, never()).save(any());
        verify(eventJournal, never()).append(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void permanentlyBlockedDownstreamStepsAreSkippedBeforeFailedFinalization() {
        ResearchStepEntity failed = step(StepType.RESOLVE_SECURITY, CREATED_AT);
        failed.beginAttempt(CREATED_AT.plusSeconds(2), ownerId);
        failed.fail(CREATED_AT.plusSeconds(3), ownerId);
        ResearchStepEntity blockedMarket = step(StepType.FETCH_MARKET_DATA, null);
        ResearchStepEntity blockedFundamentals = step(StepType.FETCH_FUNDAMENTALS, null);
        when(researchStepRepository.findAllByResearchJobIdForUpdate(researchId))
                .thenReturn(List.of(failed, blockedMarket, blockedFundamentals));

        service.finalizeResearch(researchId, false, false);

        assertThat(research.getStatus()).isEqualTo(ResearchStatus.FAILED);
        assertThat(blockedMarket.getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(blockedFundamentals.getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(blockedMarket.getSkipReason())
                .isEqualTo("UPSTREAM_DEPENDENCY_UNSATISFIED");
        assertThat(blockedFundamentals.getSkipReason())
                .isEqualTo("UPSTREAM_DEPENDENCY_UNSATISFIED");
        verify(researchStepRepository).saveAll(List.of(blockedMarket, blockedFundamentals));
        verify(eventJournal).append(
                any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void satisfiedButNotYetUnlockedPendingStepStillBlocksFinalization() {
        ResearchStepEntity succeeded = step(StepType.RESOLVE_SECURITY, CREATED_AT);
        succeeded.beginAttempt(CREATED_AT.plusSeconds(2), ownerId);
        succeeded.succeed(OUTPUT_HASH, CREATED_AT.plusSeconds(3), ownerId);
        ResearchStepEntity awaitingUnlock = step(StepType.FETCH_MARKET_DATA, null);
        when(researchStepRepository.findAllByResearchJobIdForUpdate(researchId))
                .thenReturn(List.of(succeeded, awaitingUnlock));

        assertThatThrownBy(() -> service.finalizeResearch(researchId, false, false))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("runnable or running steps remain");

        assertThat(research.getStatus()).isEqualTo(ResearchStatus.QUEUED);
        assertThat(awaitingUnlock.getStatus()).isEqualTo(StepStatus.PENDING);
        verify(researchStepRepository, never()).saveAll(any());
        verify(eventJournal, never()).append(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void futureRetryAvailabilityStillBlocksFinalization() {
        ResearchStepEntity waitingForRetry = step(
                StepType.RESOLVE_SECURITY,
                FINALIZED_AT.plusSeconds(300)
        );
        when(researchStepRepository.findAllByResearchJobIdForUpdate(researchId))
                .thenReturn(List.of(waitingForRetry));

        assertThatThrownBy(() -> service.finalizeResearch(researchId, false, false))
                .isInstanceOf(InvalidStateTransitionException.class);

        assertThat(waitingForRetry.getStatus()).isEqualTo(StepStatus.PENDING);
        verify(researchStepRepository, never()).saveAll(any());
    }

    @Test
    void phaseTwoCannotPublishSuccessfulTerminalStateWithoutAReportPublication() {
        assertThatThrownBy(() -> service.finalizeResearch(researchId, true, true))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("atomically published")
                .hasMessageContaining("Phase 3");
        assertThatThrownBy(() -> service.finalizeResearch(researchId, false, true))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("atomically published");

        assertThat(research.getStatus()).isEqualTo(ResearchStatus.QUEUED);
        verifyNoInteractions(researchStepRepository);
        verify(researchJobRepository, never()).save(any());
        verify(eventJournal, never()).append(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void cooperativeCancellationStillConvergesWhenNoActiveStepRemains() {
        research.requestCancellation(CREATED_AT.plusSeconds(2), ownerId);
        when(researchStepRepository.existsByResearchJobIdAndStatusIn(any(), any()))
                .thenReturn(false);

        boolean settled = service.confirmCancellationIfSettled(researchId);

        assertThat(settled).isTrue();
        assertThat(research.getStatus()).isEqualTo(ResearchStatus.CANCELLED);
        assertThat(research.getCompletedAt()).isEqualTo(FINALIZED_AT);
        verify(eventJournal).append(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private ResearchStepEntity step(StepType type, Instant availableAt) {
        return ResearchStepEntity.create(
                UUID.randomUUID(),
                researchId,
                type,
                INPUT_HASH,
                1,
                "{}",
                "phase2-test-v1",
                0,
                3,
                availableAt,
                CREATED_AT,
                ownerId
        );
    }

    private ResearchStepEntity succeededStep(StepType type) {
        ResearchStepEntity step = step(type, CREATED_AT);
        step.beginAttempt(CREATED_AT.plusSeconds(2), ownerId);
        step.succeed(OUTPUT_HASH, CREATED_AT.plusSeconds(3), ownerId);
        return step;
    }

    private void movePublicProjectionToFilings() {
        research.transitionTo(
                ResearchStatus.RESOLVING_SECURITY,
                StepType.RESOLVE_SECURITY.progress(),
                StepType.RESOLVE_SECURITY,
                CREATED_AT.plusSeconds(2),
                ownerId
        );
        research.transitionTo(
                ResearchStatus.FETCHING_MARKET_DATA,
                StepType.FETCH_MARKET_DATA.progress(),
                StepType.FETCH_MARKET_DATA,
                CREATED_AT.plusSeconds(3),
                ownerId
        );
        research.transitionTo(
                ResearchStatus.FETCHING_FUNDAMENTALS,
                StepType.FETCH_FUNDAMENTALS.progress(),
                StepType.FETCH_FUNDAMENTALS,
                CREATED_AT.plusSeconds(4),
                ownerId
        );
        research.transitionTo(
                ResearchStatus.FETCHING_FILINGS,
                StepType.FETCH_FILINGS.progress(),
                StepType.FETCH_FILINGS,
                CREATED_AT.plusSeconds(5),
                ownerId
        );
    }
}
