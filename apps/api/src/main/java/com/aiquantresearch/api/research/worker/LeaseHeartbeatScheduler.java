package com.aiquantresearch.api.research.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class LeaseHeartbeatScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LeaseHeartbeatScheduler.class);
    private static final int SELF_FENCE_AFTER_FAILURES = 2;

    private final DurableQueueClient queueClient;
    private final ActiveLeaseRegistry registry;
    private final WorkerProperties properties;

    public LeaseHeartbeatScheduler(
            DurableQueueClient queueClient,
            ActiveLeaseRegistry registry,
            WorkerProperties properties
    ) {
        this.queueClient = queueClient;
        this.registry = registry;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${app.worker.heartbeat-delay:10s}",
            initialDelayString = "${app.worker.heartbeat-delay:10s}"
    )
    public void heartbeat() {
        int leaseSeconds = Math.toIntExact(properties.leaseDuration().toSeconds());
        for (var active : registry.snapshot()) {
            try {
                var result = queueClient.heartbeat(
                        active.claim().attemptId(),
                        active.claim().leaseToken(),
                        leaseSeconds
                );
                if (result.accepted()) {
                    registry.recordHeartbeatSuccess(active.claim().attemptId());
                } else {
                    registry.markStale(active.claim().attemptId());
                }
                if (result.cancellationRequested()) {
                    registry.markCancellationRequested(active.claim().attemptId());
                }
            } catch (RuntimeException exception) {
                int failures = registry.recordHeartbeatFailure(active.claim().attemptId());
                if (failures >= SELF_FENCE_AFTER_FAILURES) {
                    registry.markStale(active.claim().attemptId());
                }
                LOGGER.warn(
                        "Lease heartbeat failed attemptId={} consecutiveFailures={} "
                                + "selfFenced={} errorType={}",
                        active.claim().attemptId(),
                        failures,
                        failures >= SELF_FENCE_AFTER_FAILURES,
                        exception.getClass().getSimpleName()
                );
            }
        }
    }
}
