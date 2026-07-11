package com.aiquantresearch.api.research.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.aiquantresearch.api.research.domain.StepType;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LeaseHeartbeatSchedulerTest {

    @Mock
    private DurableQueueClient queueClient;

    private ActiveLeaseRegistry registry;
    private LeaseHeartbeatScheduler scheduler;
    private QueueClaim claim;

    @BeforeEach
    void setUp() {
        registry = new ActiveLeaseRegistry();
        claim = new QueueClaim(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                Instant.parse("2026-07-10T12:01:00Z"),
                StepType.RESOLVE_SECURITY,
                "a".repeat(64),
                "phase3-test",
                1,
                JsonNodeFactory.instance.objectNode()
        );
        registry.register(claim);
        scheduler = new LeaseHeartbeatScheduler(queueClient, registry, properties());
    }

    @Test
    void selfFencesAfterTwoConsecutiveHeartbeatExceptions() {
        when(queueClient.heartbeat(eq(claim.attemptId()), eq(claim.leaseToken()), anyInt()))
                .thenThrow(new IllegalStateException("network unavailable"));

        scheduler.heartbeat();
        assertThat(registry.stale(claim.attemptId())).isFalse();

        scheduler.heartbeat();
        assertThat(registry.stale(claim.attemptId())).isTrue();
    }

    @Test
    void acceptedHeartbeatResetsTheConsecutiveFailureCounter() {
        when(queueClient.heartbeat(eq(claim.attemptId()), eq(claim.leaseToken()), anyInt()))
                .thenThrow(new IllegalStateException("first failure"))
                .thenReturn(new HeartbeatResult(
                        "HEARTBEAT_ACCEPTED",
                        false,
                        Instant.parse("2026-07-10T12:02:00Z")
                ))
                .thenThrow(new IllegalStateException("failure after recovery"));

        scheduler.heartbeat();
        scheduler.heartbeat();
        scheduler.heartbeat();

        assertThat(registry.stale(claim.attemptId())).isFalse();
    }

    @Test
    void rejectedHeartbeatFencesImmediatelyAndPropagatesCancellation() {
        when(queueClient.heartbeat(eq(claim.attemptId()), eq(claim.leaseToken()), anyInt()))
                .thenReturn(new HeartbeatResult("STALE_LEASE", true, null));

        scheduler.heartbeat();

        assertThat(registry.stale(claim.attemptId())).isTrue();
        assertThat(registry.cancellationRequested(claim.attemptId())).isTrue();
    }

    private static WorkerProperties properties() {
        return new WorkerProperties(
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
    }
}
