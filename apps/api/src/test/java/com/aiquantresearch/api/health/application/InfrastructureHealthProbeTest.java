package com.aiquantresearch.api.health.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

class InfrastructureHealthProbeTest {

    @Test
    void databaseProbeExecutesSelectOne() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject("select 1", Integer.class)).thenReturn(1);

        var probe = new DatabaseHealthProbe(jdbcTemplate);

        assertThat(probe.probe()).isEqualTo("Database reachable");
        assertThat(probe.componentName()).isEqualTo("database");
        assertThat(probe.critical()).isTrue();
    }

    @Test
    void durableQueueProbeRequiresAllFiveFunctionNames() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(5L);

        var probe = new DurableQueueHealthProbe(jdbcTemplate);

        assertThat(probe.probe()).isEqualTo("Durable queue API available");
        assertThat(probe.componentName()).isEqualTo("durableQueue");
        assertThat(probe.critical()).isTrue();
    }

    @Test
    void durableQueueProbeRejectsIncompleteApi() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(4L);

        var probe = new DurableQueueHealthProbe(jdbcTemplate);

        assertThatThrownBy(probe::probe)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable queue API is incomplete");
    }

    @Test
    void redisProbeRequiresPongAndClosesConnection() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");

        var probe = new RedisHealthProbe(connectionFactory);

        assertThat(probe.probe()).isEqualTo("Redis reachable");
        assertThat(probe.componentName()).isEqualTo("redis");
        assertThat(probe.critical()).isFalse();
        verify(connection).close();
    }
}
