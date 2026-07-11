package com.aiquantresearch.api.research.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiquantresearch.api.support.PostgresRedisIntegrationTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ResourceLock("durable-postgres-queue")
class OutboxRelayIT extends PostgresRedisIntegrationTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void relaysAPostgresTimestamptzEventAndMarksItPublished() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        jdbc.update("""
                insert into outbox_events (
                    id, aggregate_type, aggregate_id, event_type, payload_json
                ) values (?, 'TEST', ?, 'TEST_PUBLISHED', '{"status":"COMPLETED"}'::jsonb)
                """, eventId, aggregateId);
        List<OutboxDomainEvent> published = new CopyOnWriteArrayList<>();
        var relay = new OutboxRelayScheduler(
                jdbc,
                objectMapper,
                event -> published.add((OutboxDomainEvent) event),
                new SimpleMeterRegistry(),
                Clock.systemUTC(),
                1_000
        );

        relay.relay();

        assertThat(published).anySatisfy(event -> {
            assertThat(event.id()).isEqualTo(eventId);
            assertThat(event.aggregateId()).isEqualTo(aggregateId);
        });
        assertThat(jdbc.queryForObject(
                "select published_at is not null from outbox_events where id = ?",
                Boolean.class,
                eventId
        )).isTrue();
    }
}
