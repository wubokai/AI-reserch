package com.aiquantresearch.api.research.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiquantresearch.api.research.domain.InvalidDomainStateException;
import com.aiquantresearch.api.research.domain.StepStatus;
import com.aiquantresearch.api.research.domain.StepType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResearchStepEntityTest {

    private static final String INPUT_HASH = "a".repeat(64);
    private static final String OUTPUT_HASH = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    @Test
    void successfulOutputIsIdempotentButCannotBeOverwritten() {
        UUID actorId = UUID.randomUUID();
        ResearchStepEntity step = newStep(actorId, NOW);
        step.beginAttempt(NOW.plusSeconds(1), actorId);
        step.succeed(OUTPUT_HASH, NOW.plusSeconds(2), actorId);
        step.succeed(OUTPUT_HASH, NOW.plusSeconds(3), actorId);

        assertThat(step.getStatus()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(step.getSuccessfulOutputHash()).isEqualTo(OUTPUT_HASH);
        assertThatThrownBy(() -> step.succeed("c".repeat(64), NOW.plusSeconds(4), actorId))
                .isInstanceOf(InvalidDomainStateException.class);
    }

    @Test
    void manualRetryReusesSuccessfulStepWhenExecutionInputIsUnchanged() {
        UUID actorId = UUID.randomUUID();
        ResearchStepEntity step = newStep(actorId, NOW);
        step.beginAttempt(NOW.plusSeconds(1), actorId);
        step.succeed(OUTPUT_HASH, NOW.plusSeconds(2), actorId);

        boolean shouldRun = step.prepareManualRetry(
                INPUT_HASH,
                "phase2-v1",
                1,
                true,
                NOW.plusSeconds(3),
                actorId
        );

        assertThat(shouldRun).isFalse();
        assertThat(step.getStatus()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(step.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void changedInputCreatesNewPendingExecutionWithoutErasingAttemptHistory() {
        UUID actorId = UUID.randomUUID();
        ResearchStepEntity step = newStep(actorId, NOW);
        step.beginAttempt(NOW.plusSeconds(1), actorId);
        step.fail(NOW.plusSeconds(2), actorId);

        boolean shouldRun = step.prepareManualRetry(
                "d".repeat(64),
                "phase2-v2",
                2,
                true,
                NOW.plusSeconds(3),
                actorId
        );

        assertThat(shouldRun).isTrue();
        assertThat(step.getStatus()).isEqualTo(StepStatus.PENDING);
        assertThat(step.getAttemptCount()).isEqualTo(1);
        assertThat(step.getMaxAttempts()).isEqualTo(5);
    }

    private ResearchStepEntity newStep(UUID actorId, Instant now) {
        return ResearchStepEntity.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                StepType.RESOLVE_SECURITY,
                INPUT_HASH,
                1,
                "{}",
                "phase2-v1",
                0,
                3,
                now,
                now,
                actorId
        );
    }
}
