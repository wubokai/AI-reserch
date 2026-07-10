package com.aiquantresearch.api.research.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class ResearchReaperScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResearchReaperScheduler.class);

    private final DurableQueueClient queueClient;
    private final WorkerProperties properties;

    public ResearchReaperScheduler(DurableQueueClient queueClient, WorkerProperties properties) {
        this.queueClient = queueClient;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${app.worker.reaper-delay:15s}",
            initialDelayString = "${app.worker.reaper-delay:15s}"
    )
    public void reap() {
        int reaped = queueClient.reapExpired(
                100,
                properties.retryBaseDelaySeconds(),
                properties.retryMaxDelaySeconds()
        );
        if (reaped > 0) {
            LOGGER.warn("Reaped {} expired research step leases", reaped);
        }
    }
}
