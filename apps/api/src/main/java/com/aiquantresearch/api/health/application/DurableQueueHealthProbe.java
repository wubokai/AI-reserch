package com.aiquantresearch.api.health.application;

import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(20)
final class DurableQueueHealthProbe implements HealthProbe {

    private static final long REQUIRED_FUNCTION_COUNT = 5L;
    private static final String REQUIRED_FUNCTIONS_SQL = """
            select count(distinct p.proname)
              from pg_catalog.pg_proc p
              join pg_catalog.pg_namespace n
                on n.oid = p.pronamespace
             where n.nspname = 'queue_v1'
               and p.proname in (
                   'claim_step',
                   'heartbeat',
                   'complete_step',
                   'fail_step',
                   'reap_expired'
               )
            """;

    private final JdbcTemplate jdbcTemplate;

    DurableQueueHealthProbe(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String componentName() {
        return "durableQueue";
    }

    @Override
    public boolean critical() {
        return true;
    }

    @Override
    public String probe() {
        Long functionCount = jdbcTemplate.queryForObject(REQUIRED_FUNCTIONS_SQL, Long.class);
        if (functionCount == null || functionCount != REQUIRED_FUNCTION_COUNT) {
            throw new IllegalStateException("Durable queue API is incomplete");
        }
        return "Durable queue API available";
    }
}
