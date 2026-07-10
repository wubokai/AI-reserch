package com.aiquantresearch.api.research.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aiquantresearch.api.research.domain.ReportDepth;
import com.aiquantresearch.api.research.domain.ResearchLocale;
import com.aiquantresearch.api.research.domain.ResearchPeriod;
import com.aiquantresearch.api.research.domain.ResearchStatus;
import com.aiquantresearch.api.research.domain.StepType;
import com.aiquantresearch.api.research.persistence.ResearchJobEntity;
import com.aiquantresearch.api.research.persistence.ResearchJobRepository;
import com.aiquantresearch.api.research.persistence.ResearchStepEntity;
import com.aiquantresearch.api.research.persistence.ResearchStepRepository;
import com.aiquantresearch.api.shared.config.ApplicationProperties;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResearchCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    @Mock
    private ResearchJobRepository researchJobRepository;

    @Mock
    private ResearchStepRepository researchStepRepository;

    @Mock
    private OwnerProvisioningService ownerProvisioningService;

    @Mock
    private SecurityPrecheckService securityPrecheckService;

    @Mock
    private IdempotencyBoundary idempotencyBoundary;

    @Mock
    private ResearchEventJournal eventJournal;

    @Mock
    private ResearchQueryService researchQueryService;

    private ObjectMapper objectMapper;
    private ResearchCommandService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        var hashService = new CanonicalHashService(objectMapper);
        service = new ResearchCommandService(
                researchJobRepository,
                researchStepRepository,
                ownerProvisioningService,
                securityPrecheckService,
                idempotencyBoundary,
                hashService,
                eventJournal,
                researchQueryService,
                new ApplicationProperties("api", "test", DataMode.MOCK),
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void creationPersistsElevenStepsAndAppendsAtomicResearchEvent() {
        UUID ownerId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        when(idempotencyBoundary.reserve(any(), eq(NOW)))
                .thenReturn(IdempotencyReservation.acquired(reservationId));

        CommandResult<ResearchAcceptedView> result = service.create(
                ownerId,
                "demo-user",
                "demo-user@local.invalid",
                "idem-create-1",
                command()
        );

        assertThat(result.idempotencyReplayed()).isFalse();
        assertThat(result.value().status()).isEqualTo(ResearchStatus.QUEUED);
        assertThat(result.value().dataMode()).isEqualTo(DataMode.MOCK);
        verify(securityPrecheckService).validate("MU", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<ResearchStepEntity>> stepsCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(researchStepRepository).saveAll(stepsCaptor.capture());
        List<ResearchStepEntity> steps = new java.util.ArrayList<>();
        stepsCaptor.getValue().forEach(steps::add);
        assertThat(steps).hasSize(StepType.values().length);
        assertThat(steps.getFirst().getStepType()).isEqualTo(StepType.RESOLVE_SECURITY);
        assertThat(steps.getFirst().getAvailableAt()).isEqualTo(NOW);
        assertThat(steps.subList(1, steps.size()))
                .allMatch(step -> step.getAvailableAt() == null);

        verify(eventJournal).append(
                eq(result.value().researchId()),
                eq(ResearchEventJournal.ActorType.USER),
                eq(ownerId),
                eq("RESEARCH_CREATED"),
                eq("RESEARCH_QUEUED"),
                any(),
                any(),
                eq(NOW)
        );
        verify(idempotencyBoundary).complete(
                eq(reservationId),
                eq(202),
                any(),
                eq(result.value().researchId()),
                eq(NOW)
        );
    }

    @Test
    void idempotencyReplayDoesNotPersistOrAppendAgain() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID researchId = UUID.randomUUID();
        var accepted = new ResearchAcceptedView(
                researchId,
                ResearchStatus.QUEUED,
                DataMode.MOCK,
                NOW
        );
        when(idempotencyBoundary.reserve(any(), eq(NOW))).thenReturn(
                IdempotencyReservation.replay(
                        UUID.randomUUID(),
                        202,
                        objectMapper.writeValueAsString(accepted),
                        researchId
                )
        );

        CommandResult<ResearchAcceptedView> result = service.create(
                ownerId,
                "demo-user@local.invalid",
                "idem-create-1",
                command()
        );

        assertThat(result.idempotencyReplayed()).isTrue();
        assertThat(result.value()).isEqualTo(accepted);
        verify(researchJobRepository, never()).save(any(ResearchJobEntity.class));
        verify(researchStepRepository, never()).saveAll(any());
        verifyNoInteractions(securityPrecheckService);
        verify(eventJournal, never()).append(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void retryReusesSuccessfulStepWithCurrentFingerprint() {
        RetryFixture fixture = createRetryFixture();
        ResearchStepEntity successful = fixture.steps().getFirst();
        successful.beginAttempt(NOW, fixture.ownerId());
        successful.succeed("b".repeat(64), NOW, fixture.ownerId());
        String originalInputHash = successful.getInputHash();
        String originalOutputHash = successful.getSuccessfulOutputHash();
        int originalMaxAttempts = successful.getMaxAttempts();
        String originalDownstreamInputHash = fixture.steps().get(1).getInputHash();
        fixture.research().transitionTo(
                ResearchStatus.FAILED,
                0,
                null,
                NOW,
                fixture.ownerId()
        );
        when(researchJobRepository.findOwnedForUpdate(
                fixture.research().getId(),
                fixture.ownerId()
        )).thenReturn(Optional.of(fixture.research()));
        when(researchStepRepository.findAllByResearchJobIdForUpdate(
                fixture.research().getId()
        )).thenReturn(fixture.steps());

        service.retry(
                fixture.ownerId(),
                fixture.research().getId(),
                "idem-retry-current",
                RetryResearchCommand.fromFirstFailedStep()
        );

        assertThat(successful.getStatus()).isEqualTo(com.aiquantresearch.api.research.domain.StepStatus.SUCCEEDED);
        assertThat(successful.getInputHash()).isEqualTo(originalInputHash);
        assertThat(successful.getSuccessfulOutputHash()).isEqualTo(originalOutputHash);
        assertThat(successful.getAttemptCount()).isEqualTo(1);
        assertThat(successful.getMaxAttempts()).isEqualTo(originalMaxAttempts);
        assertThat(fixture.research().getCurrentStep()).isEqualTo(StepType.FETCH_MARKET_DATA);
        assertThat(fixture.steps().get(1).getAvailableAt()).isEqualTo(NOW);
        String requestHash = new CanonicalHashService(objectMapper)
                .hashText(fixture.research().getRequestJson());
        String expectedDownstreamHash = service.stepInputHash(
                requestHash,
                StepType.FETCH_MARKET_DATA,
                ResearchCommandService.implementationVersion(StepType.FETCH_MARKET_DATA),
                ResearchCommandService.successfulOutputFingerprint(originalOutputHash)
        );
        assertThat(fixture.steps().get(1).getInputHash())
                .isNotEqualTo(originalDownstreamInputHash)
                .isEqualTo(expectedDownstreamHash);
    }

    @Test
    void retryRequeuesSuccessfulStepWhenImplementationVersionIsStale() {
        RetryFixture fixture = createRetryFixture();
        ResearchStepEntity generated = fixture.steps().getFirst();
        ResearchStepEntity stale = ResearchStepEntity.create(
                generated.getId(),
                fixture.research().getId(),
                generated.getStepType(),
                generated.getInputHash(),
                generated.getPayloadVersion(),
                generated.getPayloadJson(),
                "phase1-resolve-security-v0",
                generated.getPriority(),
                generated.getMaxAttempts(),
                NOW,
                NOW,
                fixture.ownerId()
        );
        stale.beginAttempt(NOW, fixture.ownerId());
        stale.succeed("c".repeat(64), NOW, fixture.ownerId());
        fixture.steps().set(0, stale);
        fixture.research().transitionTo(
                ResearchStatus.FAILED,
                0,
                null,
                NOW,
                fixture.ownerId()
        );
        when(researchJobRepository.findOwnedForUpdate(
                fixture.research().getId(),
                fixture.ownerId()
        )).thenReturn(Optional.of(fixture.research()));
        when(researchStepRepository.findAllByResearchJobIdForUpdate(
                fixture.research().getId()
        )).thenReturn(fixture.steps());

        service.retry(
                fixture.ownerId(),
                fixture.research().getId(),
                "idem-retry-stale",
                new RetryResearchCommand(null, "  deploy\n upgrade  ")
        );

        assertThat(stale.getStatus()).isEqualTo(com.aiquantresearch.api.research.domain.StepStatus.PENDING);
        assertThat(stale.getSuccessfulOutputHash()).isNull();
        assertThat(stale.getImplementationVersion())
                .isEqualTo(ResearchCommandService.implementationVersion(StepType.RESOLVE_SECURITY));
        assertThat(stale.getAttemptCount()).isEqualTo(1);
        assertThat(stale.getMaxAttempts()).isEqualTo(6);
        assertThat(stale.getAvailableAt()).isEqualTo(NOW);
        assertThat(fixture.research().getCurrentStep()).isEqualTo(StepType.RESOLVE_SECURITY);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventJournal).append(
                eq(fixture.research().getId()),
                eq(ResearchEventJournal.ActorType.USER),
                eq(fixture.ownerId()),
                eq("RETRY_REQUESTED"),
                eq("RESEARCH_RETRY_QUEUED"),
                metadata.capture(),
                payload.capture(),
                eq(NOW)
        );
        assertThat(metadata.getValue()).containsEntry("reason", "deploy upgrade");
        assertThat((List<?>) payload.getValue().get("changedSteps")).isNotEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = ResearchStatus.class,
            names = {"COMPLETED", "PARTIALLY_COMPLETED", "CANCELLED"}
    )
    void cancellingAnAlreadySuccessfulOrCancelledResearchIsAnAcceptedNoOp(
            ResearchStatus terminalStatus
    ) {
        UUID ownerId = UUID.randomUUID();
        UUID researchId = UUID.randomUUID();
        ResearchJobEntity terminal = org.mockito.Mockito.mock(ResearchJobEntity.class);
        ResearchStatusView snapshot = terminalStatusView(researchId, terminalStatus);
        when(idempotencyBoundary.reserve(any(), eq(NOW)))
                .thenReturn(IdempotencyReservation.acquired(UUID.randomUUID()));
        when(researchJobRepository.findOwnedForUpdate(researchId, ownerId))
                .thenReturn(Optional.of(terminal));
        when(terminal.getStatus()).thenReturn(terminalStatus);
        when(researchQueryService.status(ownerId, researchId)).thenReturn(snapshot);

        CommandResult<ResearchStatusView> result = service.cancel(
                ownerId,
                researchId,
                "idem-cancelled-no-op",
                CancelResearchCommand.withoutReason()
        );

        assertThat(result.value()).isEqualTo(snapshot);
        assertThat(result.idempotencyReplayed()).isFalse();
        verify(researchStepRepository, never()).findAllByResearchJobIdForUpdate(any());
        verify(eventJournal, never()).append(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void cancellingAFailedResearchRemainsAConflict() {
        UUID ownerId = UUID.randomUUID();
        UUID researchId = UUID.randomUUID();
        ResearchJobEntity failed = org.mockito.Mockito.mock(ResearchJobEntity.class);
        when(idempotencyBoundary.reserve(any(), eq(NOW)))
                .thenReturn(IdempotencyReservation.acquired(UUID.randomUUID()));
        when(researchJobRepository.findOwnedForUpdate(researchId, ownerId))
                .thenReturn(Optional.of(failed));
        when(failed.getStatus()).thenReturn(ResearchStatus.FAILED);

        assertThatThrownBy(() -> service.cancel(
                ownerId,
                researchId,
                "idem-failed-conflict",
                CancelResearchCommand.withoutReason()
        )).isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("FAILED");

        verify(researchQueryService, never()).status(any(), any());
        verify(researchStepRepository, never()).findAllByResearchJobIdForUpdate(any());
    }

    @Test
    void softDeleteLocksTheOwnerScopedResearchBeforeMutatingIt() {
        UUID ownerId = UUID.randomUUID();
        UUID researchId = UUID.randomUUID();
        ResearchJobEntity terminal = org.mockito.Mockito.mock(ResearchJobEntity.class);
        when(researchJobRepository.findOwnedForUpdate(researchId, ownerId))
                .thenReturn(Optional.of(terminal));
        when(terminal.getStatus()).thenReturn(ResearchStatus.CANCELLED);

        service.softDelete(ownerId, researchId, "  retention\n cleanup  ");

        verify(researchJobRepository).findOwnedForUpdate(researchId, ownerId);
        verify(researchJobRepository, never()).findByIdAndOwnerId(researchId, ownerId);
        verify(terminal).softDelete(NOW, ownerId, "retention cleanup");
        verify(researchJobRepository).save(terminal);
        verify(eventJournal).append(
                eq(researchId),
                eq(ResearchEventJournal.ActorType.USER),
                eq(ownerId),
                eq("SOFT_DELETED"),
                eq("RESEARCH_SOFT_DELETED"),
                any(),
                eq(Map.of("researchId", researchId.toString())),
                eq(NOW)
        );
    }

    @Test
    void softDeleteIsANoOpWhenConcurrentDeleteWinsTheOwnerScopedLock() {
        UUID ownerId = UUID.randomUUID();
        UUID researchId = UUID.randomUUID();
        ResearchJobEntity alreadyDeleted = org.mockito.Mockito.mock(ResearchJobEntity.class);
        when(researchJobRepository.findOwnedForUpdate(researchId, ownerId))
                .thenReturn(Optional.empty());
        when(researchJobRepository.findByIdAndOwnerId(researchId, ownerId))
                .thenReturn(Optional.of(alreadyDeleted));
        when(alreadyDeleted.getDeletedAt()).thenReturn(NOW.minusSeconds(1));

        service.softDelete(ownerId, researchId, "duplicate delete");

        verify(researchJobRepository).findOwnedForUpdate(researchId, ownerId);
        verify(researchJobRepository).findByIdAndOwnerId(researchId, ownerId);
        verify(alreadyDeleted, never()).softDelete(any(), any(), any());
        verify(researchJobRepository, never()).save(any(ResearchJobEntity.class));
        verifyNoInteractions(eventJournal);
    }

    private RetryFixture createRetryFixture() {
        UUID ownerId = UUID.randomUUID();
        when(idempotencyBoundary.reserve(any(), eq(NOW))).thenReturn(
                IdempotencyReservation.acquired(UUID.randomUUID()),
                IdempotencyReservation.acquired(UUID.randomUUID())
        );
        CommandResult<ResearchAcceptedView> created = service.create(
                ownerId,
                "demo-user",
                "demo-user@local.invalid",
                "idem-create-fixture",
                command()
        );
        ArgumentCaptor<ResearchJobEntity> researchCaptor = ArgumentCaptor.forClass(
                ResearchJobEntity.class
        );
        verify(researchJobRepository).save(researchCaptor.capture());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<ResearchStepEntity>> stepsCaptor = ArgumentCaptor.forClass(
                Iterable.class
        );
        verify(researchStepRepository).saveAll(stepsCaptor.capture());
        List<ResearchStepEntity> steps = new java.util.ArrayList<>();
        stepsCaptor.getValue().forEach(steps::add);
        assertThat(researchCaptor.getValue().getId()).isEqualTo(created.value().researchId());
        return new RetryFixture(ownerId, researchCaptor.getValue(), steps);
    }

    private static CreateResearchCommand command() {
        return new CreateResearchCommand(
                "分析 MU 未来十二个月的增长动力和主要风险",
                "MU",
                null,
                ResearchLocale.ZH_CN,
                "QQQ",
                ResearchPeriod.FIVE_YEARS,
                null,
                null,
                ReportDepth.STANDARD,
                true,
                true,
                true
        );
    }

    private static ResearchStatusView terminalStatusView(
            UUID researchId,
            ResearchStatus status
    ) {
        return new ResearchStatusView(
                researchId,
                status,
                status == ResearchStatus.CANCELLED ? 0 : 100,
                null,
                0,
                0,
                status == ResearchStatus.CANCELLED,
                DataMode.MOCK,
                null,
                List.of(),
                NOW
        );
    }

    private record RetryFixture(
            UUID ownerId,
            ResearchJobEntity research,
            List<ResearchStepEntity> steps
    ) {
    }
}
