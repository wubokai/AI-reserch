package com.aiquantresearch.api.research.persistence;

import com.aiquantresearch.api.research.application.ResearchApplicationException;
import com.aiquantresearch.api.research.application.ResearchEventJournal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaResearchEventJournal implements ResearchEventJournal {

    private static final String REQUEST_ID_MDC_KEY = "requestId";

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    public JpaResearchEventJournal(EntityManager entityManager, ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(
            UUID researchId,
            ActorType actorType,
            UUID actorUserId,
            String auditAction,
            String eventType,
            Map<String, Object> metadata,
            Map<String, Object> payload,
            Instant occurredAt
    ) {
        Objects.requireNonNull(researchId, "researchId");
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (actorType == ActorType.USER && actorUserId == null) {
            throw new IllegalArgumentException("USER audit events require actorUserId");
        }
        String requestId = MDC.get(REQUEST_ID_MDC_KEY);
        String metadataJson = writeJson(metadata == null ? Map.of() : metadata);
        String payloadJson = writeJson(payload == null ? Map.of() : payload);

        entityManager.createNativeQuery("""
                        insert into audit_events (
                            research_job_id, actor_type, actor_user_id, action,
                            request_id, metadata_json, occurred_at
                        ) values (
                            :researchId, :actorType, :actorUserId, :action,
                            :requestId, cast(:metadataJson as jsonb), :occurredAt
                        )
                        """)
                .setParameter("researchId", researchId)
                .setParameter("actorType", actorType.name())
                .setParameter("actorUserId", actorUserId)
                .setParameter("action", requireText(auditAction, "auditAction"))
                .setParameter("requestId", requestId)
                .setParameter("metadataJson", metadataJson)
                .setParameter("occurredAt", occurredAt)
                .executeUpdate();

        entityManager.createNativeQuery("""
                        insert into outbox_events (
                            id, aggregate_type, aggregate_id, event_type, event_version,
                            payload_json, request_id, occurred_at
                        ) values (
                            :id, 'RESEARCH', :researchId, :eventType, 1,
                            cast(:payloadJson as jsonb), :requestId, :occurredAt
                        )
                        """)
                .setParameter("id", UUID.randomUUID())
                .setParameter("researchId", researchId)
                .setParameter("eventType", requireText(eventType, "eventType"))
                .setParameter("payloadJson", payloadJson)
                .setParameter("requestId", requestId)
                .setParameter("occurredAt", occurredAt)
                .executeUpdate();
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResearchApplicationException(
                    "EVENT_SERIALIZATION_FAILED",
                    "The research event could not be serialized",
                    false
            );
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
