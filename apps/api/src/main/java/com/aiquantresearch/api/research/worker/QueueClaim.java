package com.aiquantresearch.api.research.worker;

import com.aiquantresearch.api.research.domain.StepType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record QueueClaim(
        UUID researchJobId,
        UUID researchStepId,
        UUID attemptId,
        int attemptNumber,
        UUID leaseToken,
        Instant leaseExpiresAt,
        StepType stepType,
        String inputHash,
        String implementationVersion,
        int payloadVersion,
        JsonNode payload
) {
}
