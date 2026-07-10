package com.aiquantresearch.api.health.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiquantresearch.api.health.domain.ServiceStatus;
import com.aiquantresearch.api.support.PostgresRedisIntegrationTestSupport;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(RedisUnavailableHealthIT.UnreachableRedisConfiguration.class)
@ResourceLock("durable-postgres-queue")
class RedisUnavailableHealthIT extends PostgresRedisIntegrationTestSupport {

    @Autowired
    private HealthService healthService;

    @Test
    void redisOutageDegradesServiceWhilePostgresAndDurableQueueRemainHealthy() {
        HealthSnapshot snapshot = healthService.currentHealth();

        assertThat(snapshot.status()).isEqualTo(ServiceStatus.DEGRADED);
        assertThat(snapshot.components()).containsOnlyKeys(
                "database",
                "durableQueue",
                "redis"
        );
        assertThat(snapshot.components().get("database"))
                .returns(ServiceStatus.UP, HealthComponentSnapshot::status)
                .returns(true, HealthComponentSnapshot::critical);
        assertThat(snapshot.components().get("durableQueue"))
                .returns(ServiceStatus.UP, HealthComponentSnapshot::status)
                .returns(true, HealthComponentSnapshot::critical);
        assertThat(snapshot.components().get("redis"))
                .returns(ServiceStatus.DOWN, HealthComponentSnapshot::status)
                .returns(false, HealthComponentSnapshot::critical)
                .returns("Component unavailable", HealthComponentSnapshot::message);
        assertThat(REDIS.isRunning()).isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class UnreachableRedisConfiguration {

        @Bean
        @Primary
        RedisConnectionFactory unreachableRedisConnectionFactory() {
            var server = new RedisStandaloneConfiguration("127.0.0.1", 1);
            var client = LettuceClientConfiguration.builder()
                    .commandTimeout(Duration.ofMillis(250))
                    .shutdownTimeout(Duration.ZERO)
                    .build();
            return new LettuceConnectionFactory(server, client);
        }
    }
}
