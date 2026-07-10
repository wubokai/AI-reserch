package com.aiquantresearch.api.health.application;

import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(10)
final class DatabaseHealthProbe implements HealthProbe {

    private final JdbcTemplate jdbcTemplate;

    DatabaseHealthProbe(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String componentName() {
        return "database";
    }

    @Override
    public boolean critical() {
        return true;
    }

    @Override
    public String probe() {
        Integer result = jdbcTemplate.queryForObject("select 1", Integer.class);
        if (result == null || result != 1) {
            throw new IllegalStateException("Database probe returned an unexpected result");
        }
        return "Database reachable";
    }
}
