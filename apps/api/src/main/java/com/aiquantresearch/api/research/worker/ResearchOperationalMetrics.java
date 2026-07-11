package com.aiquantresearch.api.research.worker;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ResearchOperationalMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResearchOperationalMetrics.class);
    private static final List<String> STATUSES = List.of(
            "QUEUED", "RUNNING", "COMPLETED", "PARTIALLY_COMPLETED", "FAILED", "CANCELLED"
    );

    private final JdbcTemplate jdbc;
    private final Map<String, AtomicLong> jobsByStatus;
    private final AtomicLong oldestRunnableSeconds = new AtomicLong();
    private final AtomicReference<Double> llmCostUsd = new AtomicReference<>(0.0);

    public ResearchOperationalMetrics(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.jobsByStatus = STATUSES.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                status -> status,
                ignored -> new AtomicLong()
        ));
        jobsByStatus.forEach((status, value) -> Gauge.builder(
                        "research.jobs", value, AtomicLong::doubleValue)
                .description("Visible research jobs by terminal or active status")
                .tag("status", status)
                .register(meters));
        Gauge.builder("research.queue.oldest.runnable.seconds", oldestRunnableSeconds,
                        AtomicLong::doubleValue)
                .description("Age of the oldest runnable research step")
                .register(meters);
        Gauge.builder("research.llm.cost.usd", llmCostUsd, AtomicReference::get)
                .description("Cumulative persisted estimated LLM cost in USD")
                .register(meters);
    }

    @PostConstruct
    void initialize() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${app.observability.snapshot-delay:30s}")
    void refresh() {
        try {
            jobsByStatus.values().forEach(value -> value.set(0L));
            List<Map<String, Object>> counts = jdbc.queryForList("""
                    select status, count(*) as job_count
                      from research_jobs
                     where deleted_at is null
                     group by status
                    """);
            counts.forEach(row -> {
                AtomicLong metric = jobsByStatus.get(String.valueOf(row.get("status")));
                Object count = row.get("job_count");
                if (metric != null && count instanceof Number number) {
                    metric.set(number.longValue());
                }
            });
            oldestRunnableSeconds.set(number(jdbc.queryForObject("""
                    select coalesce(extract(epoch from (
                        statement_timestamp() - min(available_at)
                    )), 0)::bigint
                      from research_steps
                     where status = 'PENDING'
                       and available_at is not null
                       and available_at <= statement_timestamp()
                    """, Long.class)));
            Double cost = jdbc.queryForObject("""
                    select coalesce(sum(estimated_cost_usd), 0)::double precision
                      from llm_calls
                     where status in ('SUCCEEDED', 'CACHE_HIT')
                    """, Double.class);
            llmCostUsd.set(cost == null ? 0.0 : Math.max(0.0, cost));
        } catch (DataAccessException exception) {
            LOGGER.warn("Operational metric snapshot failed errorType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private static long number(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }
}
