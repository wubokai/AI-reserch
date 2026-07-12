package com.aiquantresearch.api.research.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import com.aiquantresearch.api.research.domain.ReportDepth;
import com.aiquantresearch.api.research.domain.ResearchLocale;
import com.aiquantresearch.api.research.domain.ResearchPeriod;
import com.aiquantresearch.api.research.persistence.ResearchJobEntity;
import com.aiquantresearch.api.research.persistence.ResearchJobRepository;
import com.aiquantresearch.api.research.persistence.ResearchStepRepository;
import com.aiquantresearch.api.research.persistence.ResearchStepEntity;
import com.aiquantresearch.api.research.persistence.StepAttemptEntity;
import com.aiquantresearch.api.research.persistence.StepAttemptRepository;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ResearchQueryServiceTest {

    @Mock
    private ResearchJobRepository researchJobRepository;

    @Mock
    private ResearchStepRepository researchStepRepository;

    @Mock
    private StepAttemptRepository stepAttemptRepository;

    @Test
    void listUsesStableIdentifierTieBreakerInTheRequestedDirection() {
        UUID ownerId = UUID.randomUUID();
        when(researchJobRepository.searchOwned(
                eq(ownerId),
                anyBoolean(), anyString(),
                anyBoolean(), any(com.aiquantresearch.api.research.domain.ResearchStatus.class),
                anyBoolean(), any(Instant.class),
                anyBoolean(), any(Instant.class),
                anyBoolean(), anyString(),
                any(Pageable.class)
        )).thenReturn(Page.empty());
        var service = new ResearchQueryService(
                researchJobRepository,
                researchStepRepository,
                stepAttemptRepository,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-07-10T12:00:00Z"), ZoneOffset.UTC)
        );

        service.list(ownerId, ResearchListQuery.firstPage());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(researchJobRepository).searchOwned(
                eq(ownerId),
                anyBoolean(), anyString(),
                anyBoolean(), any(com.aiquantresearch.api.research.domain.ResearchStatus.class),
                anyBoolean(), any(Instant.class),
                anyBoolean(), any(Instant.class),
                anyBoolean(), anyString(),
                pageable.capture()
        );
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
        assertThat(pageable.getValue().getSort().getOrderFor("id").isDescending()).isTrue();
    }

    @Test
    void mixedTestDetailCarriesExplicitNonPublicationWarning() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID researchId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        CreateResearchCommand command = new CreateResearchCommand(
                "Analyze a mixed test research fixture",
                "MU",
                null,
                ResearchLocale.EN_US,
                "SPY",
                ResearchPeriod.FIVE_YEARS,
                null,
                null,
                ReportDepth.STANDARD,
                true,
                true,
                true
        );
        ResearchJobEntity research = ResearchJobEntity.create(
                researchId,
                ownerId,
                "MU",
                command.query(),
                command.locale(),
                objectMapper.writeValueAsString(command),
                DataMode.MIXED_TEST,
                now
        );
        research.queue(now);
        when(researchJobRepository.findByIdAndOwnerIdAndDeletedAtIsNull(researchId, ownerId))
                .thenReturn(Optional.of(research));
        when(researchStepRepository.findAllOwned(researchId, ownerId)).thenReturn(List.of());
        var service = new ResearchQueryService(
                researchJobRepository,
                researchStepRepository,
                stepAttemptRepository,
                objectMapper,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        ResearchDetailView detail = service.detail(ownerId, researchId);

        assertThat(detail.warnings())
                .singleElement()
                .asString()
                .contains("MIXED_TEST", "PUBLISHING AND EXPORT ARE PROHIBITED");
    }

    @Test
    void itemProjectionIncludesThePublishedReportVersion() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID researchId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        CreateResearchCommand command = new CreateResearchCommand(
                "Analyze the published Mock report version",
                "MU",
                null,
                ResearchLocale.EN_US,
                "SPY",
                ResearchPeriod.FIVE_YEARS,
                null,
                null,
                ReportDepth.STANDARD,
                true,
                true,
                true
        );
        ResearchJobEntity research = ResearchJobEntity.create(
                researchId,
                ownerId,
                "MU",
                command.query(),
                command.locale(),
                objectMapper.writeValueAsString(command),
                DataMode.MOCK,
                now
        );
        ReflectionTestUtils.setField(research, "latestReportVersion", 3);
        var service = new ResearchQueryService(
                researchJobRepository,
                researchStepRepository,
                stepAttemptRepository,
                objectMapper,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        assertThat(service.toItem(research).latestReportVersion()).isEqualTo(3);
    }

    @Test
    void detailIncludesDistinctWarningsFromSuccessfulStepManifests() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID researchId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        CreateResearchCommand command = new CreateResearchCommand(
                "Analyze a newly listed company with available history",
                "NEW",
                null,
                ResearchLocale.EN_US,
                "SPY",
                ResearchPeriod.FIVE_YEARS,
                null,
                null,
                ReportDepth.STANDARD,
                true,
                true,
                true
        );
        ResearchJobEntity research = ResearchJobEntity.create(
                researchId,
                ownerId,
                "NEW",
                command.query(),
                command.locale(),
                objectMapper.writeValueAsString(command),
                DataMode.REAL,
                now
        );
        research.queue(now);
        ResearchStepEntity step = mock(ResearchStepEntity.class);
        when(step.getId()).thenReturn(stepId);
        StepAttemptEntity attempt = mock(StepAttemptEntity.class);
        when(attempt.getResearchStepId()).thenReturn(stepId);
        when(attempt.getOutputManifestJson()).thenReturn("""
                {"warnings":[
                  "MARKET_HISTORY_SHORTER_THAN_REQUESTED",
                  "MARKET_HISTORY_SHORTER_THAN_REQUESTED"
                ]}
                """);
        when(researchJobRepository.findByIdAndOwnerIdAndDeletedAtIsNull(researchId, ownerId))
                .thenReturn(Optional.of(research));
        when(researchStepRepository.findAllOwned(researchId, ownerId))
                .thenReturn(List.of(step));
        when(stepAttemptRepository
                .findAllByResearchStepIdInOrderByResearchStepIdAscAttemptNumberAsc(
                        any()
                )).thenReturn(List.of(attempt));
        var service = new ResearchQueryService(
                researchJobRepository,
                researchStepRepository,
                stepAttemptRepository,
                objectMapper,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        ResearchDetailView detail = service.detail(ownerId, researchId);

        assertThat(detail.warnings())
                .containsExactly("MARKET_HISTORY_SHORTER_THAN_REQUESTED");
    }
}
