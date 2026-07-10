package com.aiquantresearch.api.research.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiquantresearch.api.research.domain.InvalidDomainStateException;
import com.aiquantresearch.api.research.domain.ResearchLocale;
import com.aiquantresearch.api.research.domain.ResearchStatus;
import com.aiquantresearch.api.research.domain.StepType;
import com.aiquantresearch.api.shared.domain.DataMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResearchJobEntityTest {

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    @Test
    void queuesAndAdvancesWithoutAllowingProgressRegression() {
        UUID ownerId = UUID.randomUUID();
        ResearchJobEntity job = newJob(ownerId);

        job.queue(NOW.plus(1, ChronoUnit.SECONDS));
        job.transitionTo(
                ResearchStatus.RESOLVING_SECURITY,
                5,
                StepType.RESOLVE_SECURITY,
                NOW.plus(2, ChronoUnit.SECONDS),
                ownerId
        );

        assertThat(job.getStatus()).isEqualTo(ResearchStatus.RESOLVING_SECURITY);
        assertThat(job.getProgress()).isEqualTo(5);
        assertThat(job.getStartedAt()).isNotNull();
        assertThatThrownBy(() -> job.transitionTo(
                ResearchStatus.RESOLVING_SECURITY,
                4,
                StepType.RESOLVE_SECURITY,
                NOW.plus(3, ChronoUnit.SECONDS),
                ownerId
        )).isInstanceOf(InvalidDomainStateException.class);
    }

    @Test
    void cancellationMustBeRequestedBeforeTerminalConfirmation() {
        UUID ownerId = UUID.randomUUID();
        ResearchJobEntity job = newJob(ownerId);
        job.queue(NOW.plusSeconds(1));

        assertThatThrownBy(() -> job.confirmCancellation(NOW.plusSeconds(2), ownerId))
                .isInstanceOf(InvalidDomainStateException.class);

        job.requestCancellation(NOW.plusSeconds(2), ownerId);
        job.confirmCancellation(NOW.plusSeconds(3), ownerId);

        assertThat(job.getStatus()).isEqualTo(ResearchStatus.CANCELLED);
        assertThat(job.getCompletedAt()).isEqualTo(NOW.plusSeconds(3));
    }

    @Test
    void manualRetryReopensOnlyRetryableResearchTerminal() {
        UUID ownerId = UUID.randomUUID();
        ResearchJobEntity job = newJob(ownerId);
        job.queue(NOW.plusSeconds(1));
        job.transitionTo(
                ResearchStatus.FAILED,
                0,
                null,
                NOW.plusSeconds(2),
                ownerId
        );

        job.prepareManualRetry(StepType.RESOLVE_SECURITY, NOW.plusSeconds(3), ownerId);

        assertThat(job.getStatus()).isEqualTo(ResearchStatus.QUEUED);
        assertThat(job.getCurrentStep()).isEqualTo(StepType.RESOLVE_SECURITY);
        assertThat(job.getProgress()).isZero();
        assertThat(job.getCompletedAt()).isNull();
    }

    private ResearchJobEntity newJob(UUID ownerId) {
        return ResearchJobEntity.create(
                UUID.randomUUID(),
                ownerId,
                "MU",
                "分析增长动力、财务质量与周期风险",
                ResearchLocale.ZH_CN,
                "{\"symbol\":\"MU\"}",
                DataMode.MOCK,
                NOW
        );
    }
}
