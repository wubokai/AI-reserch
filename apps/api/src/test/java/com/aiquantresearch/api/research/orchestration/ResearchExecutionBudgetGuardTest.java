package com.aiquantresearch.api.research.orchestration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aiquantresearch.api.research.persistence.ResearchJobEntity;
import com.aiquantresearch.api.research.persistence.ResearchJobRepository;
import com.aiquantresearch.api.research.worker.StepExecutionException;
import com.aiquantresearch.api.research.worker.WorkerProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResearchExecutionBudgetGuardTest {

    private static final UUID RESEARCH_ID = UUID.fromString(
            "10000000-0000-0000-0000-000000000001"
    );
    private static final Instant NOW = Instant.parse("2026-07-11T12:15:00Z");

    private final ResearchJobRepository researchJobs = mock(ResearchJobRepository.class);
    private final WorkerProperties properties = new WorkerProperties(
            true,
            "test-worker",
            1,
            Duration.ofMillis(250),
            Duration.ofSeconds(10),
            Duration.ofSeconds(15),
            Duration.ofSeconds(60),
            Duration.ofMinutes(15),
            1,
            30
    );
    private final ResearchExecutionBudgetGuard guard = new ResearchExecutionBudgetGuard(
            researchJobs,
            properties,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void allowsAQueuedJobWhoseExecutionClockHasNotStarted() {
        ResearchJobEntity research = mock(ResearchJobEntity.class);
        when(researchJobs.findById(RESEARCH_ID)).thenReturn(Optional.of(research));
        when(research.getStartedAt()).thenReturn(null);

        assertThatCode(() -> guard.assertWithinBudget(RESEARCH_ID)).doesNotThrowAnyException();
    }

    @Test
    void allowsExecutionImmediatelyBeforeTheDeadline() {
        ResearchJobEntity research = mock(ResearchJobEntity.class);
        when(researchJobs.findById(RESEARCH_ID)).thenReturn(Optional.of(research));
        when(research.getStartedAt()).thenReturn(NOW.minus(Duration.ofMinutes(15)).plusMillis(1));

        assertThatCode(() -> guard.assertWithinBudget(RESEARCH_ID)).doesNotThrowAnyException();
    }

    @Test
    void rejectsExecutionAtTheDeadlineAsAPermanentFailure() {
        ResearchJobEntity research = mock(ResearchJobEntity.class);
        when(researchJobs.findById(RESEARCH_ID)).thenReturn(Optional.of(research));
        when(research.getStartedAt()).thenReturn(NOW.minus(Duration.ofMinutes(15)));

        assertThatThrownBy(() -> guard.assertWithinBudget(RESEARCH_ID))
                .isInstanceOfSatisfying(StepExecutionException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.code())
                            .isEqualTo(ResearchExecutionBudgetGuard.EXCEEDED_CODE);
                    org.assertj.core.api.Assertions.assertThat(exception.retryable()).isFalse();
                });
    }

    @Test
    void rejectsAMissingJobAsAPermanentFailure() {
        when(researchJobs.findById(RESEARCH_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.assertWithinBudget(RESEARCH_ID))
                .isInstanceOfSatisfying(StepExecutionException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.code())
                            .isEqualTo("RESEARCH_NOT_FOUND");
                    org.assertj.core.api.Assertions.assertThat(exception.retryable()).isFalse();
                });
    }
}
