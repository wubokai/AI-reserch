package com.aiquantresearch.api.health.application;

import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

@Component
@Order(30)
final class RedisHealthProbe implements HealthProbe {

    private final RedisConnectionFactory connectionFactory;

    RedisHealthProbe(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public String componentName() {
        return "redis";
    }

    @Override
    public boolean critical() {
        return false;
    }

    @Override
    public String probe() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String response = connection.ping();
            if (!"PONG".equalsIgnoreCase(response)) {
                throw new IllegalStateException("Redis probe returned an unexpected result");
            }
        }
        return "Redis reachable";
    }
}
