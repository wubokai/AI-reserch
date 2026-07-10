package com.aiquantresearch.api.research.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiquantresearch.api.research.domain.AttemptStatus;
import com.aiquantresearch.api.research.domain.InvalidDomainStateException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StepAttemptEntityTest {

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    @Test
    void onlyCurrentUnexpiredFencingTokenCanPublish() {
        UUID token = UUID.randomUUID();
        StepAttemptEntity attempt = newAttempt(token);

        assertThatThrownBy(() -> attempt.succeed(
                UUID.randomUUID(),
                "b".repeat(64),
                NOW.plusSeconds(1)
        )).isInstanceOf(InvalidDomainStateException.class)
                .hasMessage("STALE_LEASE");

        attempt.succeed(token, "b".repeat(64), NOW.plusSeconds(1));

        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.SUCCEEDED);
        assertThat(attempt.getDurationMs()).isEqualTo(1000L);
    }

    @Test
    void expiredLeaseCannotBeRevivedByHeartbeat() {
        UUID token = UUID.randomUUID();
        StepAttemptEntity attempt = newAttempt(token);

        assertThatThrownBy(() -> attempt.heartbeat(
                token,
                NOW.plusSeconds(20),
                NOW.plusSeconds(11)
        )).isInstanceOf(InvalidDomainStateException.class)
                .hasMessage("STALE_LEASE");

        attempt.expire(NOW.plusSeconds(11));
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.LEASE_EXPIRED);
        assertThat(attempt.isRetryable()).isTrue();
    }

    private StepAttemptEntity newAttempt(UUID token) {
        return StepAttemptEntity.start(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "a".repeat(64),
                "worker-test",
                token,
                NOW.plusSeconds(10),
                NOW
        );
    }
}
