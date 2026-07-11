package com.aiquantresearch.api.research.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiquantresearch.api.research.domain.StepType;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResearchWorkerRuntimeTest {

    @Mock
    private DurableQueueClient queueClient;

    @Mock
    private ResearchStepProcessor processor;

    @Mock
    private ExecutorService executor;

    private ActiveLeaseRegistry leases;
    private ResearchWorkerRuntime runtime;
    private QueueClaim claim;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        leases = new ActiveLeaseRegistry();
        meters = new SimpleMeterRegistry();
        runtime = new ResearchWorkerRuntime(
                queueClient,
                processor,
                leases,
                properties(),
                executor,
                meters
        );
        claim = new QueueClaim(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                Instant.parse("2026-07-10T12:01:00Z"),
                StepType.FETCH_MARKET_DATA,
                "a".repeat(64),
                "phase3-test",
                1,
                JsonNodeFactory.instance.objectNode()
        );
    }

    @Test
    void providerTimeoutIsSafelyClassifiedForRetry() {
        doThrow(new IllegalStateException("provider host and credential details"))
                .when(processor).process(claim);
        when(queueClient.fail(
                eq(claim.attemptId()),
                eq(claim.leaseToken()),
                eq(true),
                eq("UNEXPECTED_WORKER_ERROR"),
                eq("The research step could not be completed safely"),
                eq(1),
                eq(30)
        )).thenReturn(failure("RETRY_SCHEDULED", "PENDING"));

        runtime.execute(claim);

        verify(queueClient).fail(
                claim.attemptId(),
                claim.leaseToken(),
                true,
                "UNEXPECTED_WORKER_ERROR",
                "The research step could not be completed safely",
                1,
                30
        );
        assertThat(leases.snapshot()).isEmpty();
        assertThat(meters.find("research.worker.executions")
                .tag("outcome", "unexpected_failure").timer().count()).isOne();
    }

    @Test
    void evidenceOrSchemaFailureRemainsNonRetryable() {
        doThrow(new StepExecutionException(
                "REPORT_VALIDATION_FAILED",
                "The report candidate failed Evidence and numeric validation",
                false
        )).when(processor).process(claim);
        when(queueClient.fail(
                eq(claim.attemptId()),
                eq(claim.leaseToken()),
                eq(false),
                eq("REPORT_VALIDATION_FAILED"),
                eq("The report candidate failed Evidence and numeric validation"),
                eq(1),
                eq(30)
        )).thenReturn(failure("FAILED", "FAILED"));

        runtime.execute(claim);

        verify(queueClient).fail(
                claim.attemptId(),
                claim.leaseToken(),
                false,
                "REPORT_VALIDATION_FAILED",
                "The report candidate failed Evidence and numeric validation",
                1,
                30
        );
        assertThat(meters.find("research.worker.executions")
                .tag("outcome", "permanent_failure").timer().count()).isOne();
    }

    @Test
    void retryableFailureCanSucceedOnTheNextClaimWithoutLeakingLeaseState() {
        doThrow(new IllegalStateException("transient provider timeout"))
                .doNothing()
                .when(processor).process(claim);
        when(queueClient.fail(
                eq(claim.attemptId()),
                eq(claim.leaseToken()),
                eq(true),
                eq("UNEXPECTED_WORKER_ERROR"),
                eq("The research step could not be completed safely"),
                eq(1),
                eq(30)
        )).thenReturn(failure("RETRY_SCHEDULED", "PENDING"));

        runtime.execute(claim);
        runtime.execute(claim);

        verify(processor, org.mockito.Mockito.times(2)).process(claim);
        verify(queueClient).fail(
                claim.attemptId(),
                claim.leaseToken(),
                true,
                "UNEXPECTED_WORKER_ERROR",
                "The research step could not be completed safely",
                1,
                30
        );
        verify(queueClient, never()).completeAndAdvance(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        assertThat(leases.snapshot()).isEmpty();
    }

    private QueueFailure failure(String resultCode, String status) {
        return new QueueFailure(
                resultCode,
                claim.researchJobId(),
                claim.researchStepId(),
                status,
                Instant.parse("2026-07-10T12:02:00Z")
        );
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
                1,
                30
        );
    }
}
