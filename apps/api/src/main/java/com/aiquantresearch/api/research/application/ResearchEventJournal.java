package com.aiquantresearch.api.research.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Append-only audit and outbox boundary. Implementations participate in the caller transaction. */
public interface ResearchEventJournal {

    void append(
            UUID researchId,
            ActorType actorType,
            UUID actorUserId,
            String auditAction,
            String eventType,
            Map<String, Object> metadata,
            Map<String, Object> payload,
            Instant occurredAt
    );

    enum ActorType {
        USER,
        SYSTEM,
        WORKER
    }
}
