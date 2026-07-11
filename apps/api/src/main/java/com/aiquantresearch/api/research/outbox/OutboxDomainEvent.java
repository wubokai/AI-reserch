package com.aiquantresearch.api.research.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record OutboxDomainEvent(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        int eventVersion,
        JsonNode payload,
        Instant occurredAt
) {
}
