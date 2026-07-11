package com.aiquantresearch.api.research.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ResearchOperationalMetricsTest {

    @Test
    void refreshPublishesLowCardinalityQueueCompletionAndCostMetrics() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("group by status"))).thenReturn(List.of(
                Map.of("status", "COMPLETED", "job_count", 7L),
                Map.of("status", "FAILED", "job_count", 2L)
        ));
        when(jdbc.queryForObject(contains("min(available_at)"), eq(Long.class)))
                .thenReturn(45L);
        when(jdbc.queryForObject(contains("sum(estimated_cost_usd)"), eq(Double.class)))
                .thenReturn(1.25);
        var meters = new SimpleMeterRegistry();
        var metrics = new ResearchOperationalMetrics(jdbc, meters);

        metrics.refresh();

        assertThat(meters.get("research.jobs").tag("status", "COMPLETED").gauge().value())
                .isEqualTo(7.0);
        assertThat(meters.get("research.jobs").tag("status", "FAILED").gauge().value())
                .isEqualTo(2.0);
        assertThat(meters.get("research.queue.oldest.runnable.seconds").gauge().value())
                .isEqualTo(45.0);
        assertThat(meters.get("research.llm.cost.usd").gauge().value()).isEqualTo(1.25);
    }
}
