package com.aiquantresearch.api.research.worker;

import com.aiquantresearch.api.research.domain.StepType;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class ResearchWorkerRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResearchWorkerRuntime.class);
    private static final List<StepType> SUPPORTED_STEPS = List.of(StepType.values());

    private final DurableQueueClient queueClient;
    private final ResearchStepProcessor processor;
    private final ActiveLeaseRegistry activeLeases;
    private final WorkerProperties properties;
    private final ExecutorService executor;
    private final AtomicInteger activeTasks = new AtomicInteger();

    public ResearchWorkerRuntime(
            DurableQueueClient queueClient,
            ResearchStepProcessor processor,
            ActiveLeaseRegistry activeLeases,
            WorkerProperties properties,
            @Qualifier("researchWorkerExecutor") ExecutorService executor
    ) {
        this.queueClient = queueClient;
        this.processor = processor;
        this.activeLeases = activeLeases;
        this.properties = properties;
        this.executor = executor;
    }

    @Scheduled(
            fixedDelayString = "${app.worker.poll-delay:250ms}",
            initialDelayString = "${app.worker.poll-delay:250ms}"
    )
    public void poll() {
        while (activeTasks.get() < properties.concurrency()) {
            var claim = queueClient.claim(
                    properties.workerId(),
                    SUPPORTED_STEPS,
                    Math.toIntExact(properties.leaseDuration().toSeconds())
            );
            if (claim.isEmpty()) {
                return;
            }
            activeTasks.incrementAndGet();
            executor.submit(() -> execute(claim.orElseThrow()));
        }
    }

    void execute(QueueClaim claim) {
        activeLeases.register(claim);
        try {
            processor.process(claim);
        } catch (StepExecutionException exception) {
            fail(claim, exception.code(), exception.getMessage(), exception.retryable());
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Unexpected research step failure researchId={} step={} attemptId={} errorType={}",
                    claim.researchJobId(),
                    claim.stepType(),
                    claim.attemptId(),
                    exception.getClass().getSimpleName()
            );
            fail(
                    claim,
                    "UNEXPECTED_WORKER_ERROR",
                    "The research step could not be completed safely",
                    true
            );
        } finally {
            activeLeases.unregister(claim.attemptId());
            activeTasks.decrementAndGet();
        }
    }

    private void fail(
            QueueClaim claim,
            String code,
            String safeMessage,
            boolean retryable
    ) {
        var failure = queueClient.fail(
                claim.attemptId(),
                claim.leaseToken(),
                retryable,
                code,
                safeMessage,
                properties.retryBaseDelaySeconds(),
                properties.retryMaxDelaySeconds()
        );
        switch (failure.disposition()) {
            case RETRY_SCHEDULED -> LOGGER.warn(
                    "Research step retry scheduled researchId={} step={} availableAt={}",
                    claim.researchJobId(), claim.stepType(), failure.availableAt()
            );
            case STEP_FAILED -> LOGGER.error(
                    "Research step exhausted or failed permanently researchId={} step={}",
                    claim.researchJobId(), claim.stepType()
            );
            case CANCELLED -> LOGGER.info(
                    "Research step converged to cancellation researchId={} step={}",
                    claim.researchJobId(), claim.stepType()
            );
            case STALE_LEASE -> LOGGER.info(
                    "Research step failure result discarded after lease fencing researchId={} step={}",
                    claim.researchJobId(), claim.stepType()
            );
            case RESEARCH_TERMINAL -> LOGGER.info(
                    "Research step failure ignored because research is terminal researchId={} step={}",
                    claim.researchJobId(), claim.stepType()
            );
        }
    }
}
