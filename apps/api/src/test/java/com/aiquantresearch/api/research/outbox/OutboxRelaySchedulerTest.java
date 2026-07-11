package com.aiquantresearch.api.research.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

class OutboxRelaySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-07-11T12:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OutboxRelayScheduler relay = new OutboxRelayScheduler(
            jdbc,
            new ObjectMapper(),
            publisher,
            meters,
            Clock.fixed(NOW, ZoneOffset.UTC),
            100
    );

    @Test
    void publishesThenMarksAnEventWithItsStableId() {
        var pending = pending();
        when(jdbc.update(
                anyString(),
                any(java.sql.Timestamp.class),
                eq(pending.id())
        )).thenReturn(1);

        relay.relay(pending);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(
                OutboxDomainEvent.class,
                published -> {
                    assertThat(published.id()).isEqualTo(pending.id());
                    assertThat(published.payload().path("status").asText())
                            .isEqualTo("COMPLETED");
                }
        );
        assertThat(meters.counter(
                "research.outbox.relay",
                "outcome",
                "published"
        ).count()).isOne();
    }

    @Test
    void keepsAFailedEventUnpublishedAndRecordsASafeRetryCode() {
        var pending = pending();
        doThrow(new IllegalStateException("listener internals"))
                .when(publisher).publishEvent(any(Object.class));

        relay.relay(pending);

        verify(jdbc).update(
                anyString(),
                eq("OUTBOX_LISTENER_FAILED"),
                eq(pending.id())
        );
        assertThat(meters.counter(
                "research.outbox.relay",
                "outcome",
                "failed"
        ).count()).isOne();
    }

    private static OutboxRelayScheduler.PendingEvent pending() {
        return new OutboxRelayScheduler.PendingEvent(
                UUID.randomUUID(),
                "RESEARCH",
                UUID.randomUUID(),
                "REPORT_PUBLISHED",
                1,
                "{\"status\":\"COMPLETED\"}",
                NOW.minusSeconds(1)
        );
    }
}
