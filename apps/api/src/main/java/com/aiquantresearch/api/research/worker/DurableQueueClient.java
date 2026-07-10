package com.aiquantresearch.api.research.worker;

import com.aiquantresearch.api.research.domain.StepType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface DurableQueueClient {

    Optional<QueueClaim> claim(String workerId, Collection<StepType> supported, int leaseSeconds);

    HeartbeatResult heartbeat(UUID attemptId, UUID leaseToken, int leaseSeconds);

    QueueCompletion completeAndAdvance(
            UUID attemptId,
            UUID leaseToken,
            String outputHash,
            JsonNode outputManifest
    );

    QueueFailure fail(
            UUID attemptId,
            UUID leaseToken,
            boolean retryable,
            String errorCode,
            String safeMessage,
            int baseDelaySeconds,
            int maxDelaySeconds
    );

    int reapExpired(int batchSize, int baseDelaySeconds, int maxDelaySeconds);
}
