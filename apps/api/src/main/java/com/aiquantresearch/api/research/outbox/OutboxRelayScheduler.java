package com.aiquantresearch.api.research.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(
        name = {"app.worker.enabled", "app.outbox.enabled"},
        havingValue = "true",
        matchIfMissing = true
)
public class OutboxRelayScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher publisher;
    private final MeterRegistry meters;
    private final Clock clock;
    private final int batchSize;

    public OutboxRelayScheduler(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ApplicationEventPublisher publisher,
            MeterRegistry meters,
            Clock clock,
            @Value("${app.outbox.batch-size:100}") int batchSize
    ) {
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("outbox batch size must be between 1 and 1000");
        }
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.publisher = publisher;
        this.meters = meters;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${app.outbox.relay-delay:1s}",
            initialDelayString = "${app.outbox.initial-delay:2s}"
    )
    @Transactional
    public void relay() {
        var events = jdbc.query("""
                select id, aggregate_type, aggregate_id, event_type,
                       event_version, payload_json::text as payload_json, occurred_at
                  from outbox_events
                 where published_at is null
                 order by occurred_at, id
                 limit %d
                   for update skip locked
                """.formatted(batchSize), (row, ignored) -> new PendingEvent(
                row.getObject("id", UUID.class),
                row.getString("aggregate_type"),
                row.getObject("aggregate_id", UUID.class),
                row.getString("event_type"),
                row.getInt("event_version"),
                row.getString("payload_json"),
                row.getObject("occurred_at", Instant.class)
        ));
        for (PendingEvent event : events) {
            relay(event);
        }
    }

    void relay(PendingEvent event) {
        try {
            publisher.publishEvent(new OutboxDomainEvent(
                    event.id(),
                    event.aggregateType(),
                    event.aggregateId(),
                    event.eventType(),
                    event.eventVersion(),
                    objectMapper.readTree(event.payloadJson()),
                    event.occurredAt()
            ));
            int updated = jdbc.update("""
                    update outbox_events
                       set published_at = ?, publish_attempts = publish_attempts + 1,
                           last_error_code = null
                     where id = ? and published_at is null
                    """, Timestamp.from(clock.instant()), event.id());
            meters.counter(
                    "research.outbox.relay",
                    "outcome",
                    updated == 1 ? "published" : "stale"
            ).increment();
        } catch (JsonProcessingException exception) {
            recordFailure(event.id(), "OUTBOX_PAYLOAD_INVALID", exception);
        } catch (RuntimeException exception) {
            recordFailure(event.id(), "OUTBOX_LISTENER_FAILED", exception);
        }
    }

    private void recordFailure(UUID eventId, String code, Exception exception) {
        jdbc.update("""
                update outbox_events
                   set publish_attempts = publish_attempts + 1,
                       last_error_code = ?
                 where id = ? and published_at is null
                """, code, eventId);
        meters.counter("research.outbox.relay", "outcome", "failed").increment();
        LOGGER.warn("Outbox relay failed eventId={} code={} errorType={}",
                eventId, code, exception.getClass().getSimpleName());
    }

    record PendingEvent(
            UUID id,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            int eventVersion,
            String payloadJson,
            Instant occurredAt
    ) {
    }
}
