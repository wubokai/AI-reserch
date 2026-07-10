package com.aiquantresearch.api.health.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiquantresearch.api.health.domain.ServiceStatus;
import com.aiquantresearch.api.shared.config.ApplicationProperties;
import com.aiquantresearch.api.shared.domain.DataMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class HealthServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");
    private static final ApplicationProperties PROPERTIES = new ApplicationProperties(
            "ai-quant-research-api",
            "0.2.0-test",
            DataMode.MOCK
    );

    @Test
    void allComponentsUpProducesUpSnapshot() {
        var service = service(List.of(
                successful("database", true, "Database reachable"),
                successful("durableQueue", true, "Durable queue API available"),
                successful("redis", false, "Redis reachable")
        ));

        HealthSnapshot health = service.currentHealth();

        assertThat(health.status()).isEqualTo(ServiceStatus.UP);
        assertThat(health.version()).isEqualTo("0.2.0-test");
        assertThat(health.dataMode()).isEqualTo(DataMode.MOCK);
        assertThat(health.timestamp()).isEqualTo(NOW);
        assertThat(health.components()).containsOnlyKeys("database", "durableQueue", "redis");
        assertThat(health.components().values())
                .allSatisfy(component -> {
                    assertThat(component.status()).isEqualTo(ServiceStatus.UP);
                    assertThat(component.latencyMs()).isEqualTo(1L);
                });
    }

    @Test
    void criticalFailureProducesDownAndDoesNotExposeExceptionMessage() {
        var service = service(List.of(
                failing("database", true, "jdbc:postgresql://secret-host/db?password=secret"),
                successful("durableQueue", true, "Durable queue API available"),
                successful("redis", false, "Redis reachable")
        ));

        HealthSnapshot health = service.currentHealth();

        assertThat(health.status()).isEqualTo(ServiceStatus.DOWN);
        assertThat(health.components().get("database").status()).isEqualTo(ServiceStatus.DOWN);
        assertThat(health.components().get("database").critical()).isTrue();
        assertThat(health.components().get("database").message())
                .isEqualTo("Component unavailable")
                .doesNotContain("secret", "jdbc", "password");
    }

    @Test
    void nonCriticalFailureProducesDegradedSnapshot() {
        var service = service(List.of(
                successful("database", true, "Database reachable"),
                successful("durableQueue", true, "Durable queue API available"),
                failing("redis", false, "redis://secret-host:6379")
        ));

        HealthSnapshot health = service.currentHealth();

        assertThat(health.status()).isEqualTo(ServiceStatus.DEGRADED);
        assertThat(health.components().get("redis").status()).isEqualTo(ServiceStatus.DOWN);
        assertThat(health.components().get("redis").critical()).isFalse();
    }

    private static HealthService service(List<HealthProbe> probes) {
        return new HealthService(
                PROPERTIES,
                Clock.fixed(NOW, ZoneOffset.UTC),
                probes,
                deterministicNanoTime()
        );
    }

    private static LongSupplier deterministicNanoTime() {
        AtomicLong value = new AtomicLong(-1_000_000L);
        return () -> value.addAndGet(1_000_000L);
    }

    private static HealthProbe successful(String name, boolean critical, String message) {
        return probe(name, critical, () -> message);
    }

    private static HealthProbe failing(String name, boolean critical, String sensitiveMessage) {
        return probe(name, critical, () -> {
            throw new IllegalStateException(sensitiveMessage);
        });
    }

    private static HealthProbe probe(
            String name,
            boolean critical,
            java.util.function.Supplier<String> action
    ) {
        return new HealthProbe() {
            @Override
            public String componentName() {
                return name;
            }

            @Override
            public boolean critical() {
                return critical;
            }

            @Override
            public String probe() {
                return action.get();
            }
        };
    }
}
