package com.aiquantresearch.api.health.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiquantresearch.api.health.domain.ServiceStatus;
import com.aiquantresearch.api.shared.config.ApplicationProperties;
import com.aiquantresearch.api.shared.domain.DataMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class HealthServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-09T18:00:00Z");

    @Test
    void returnsConfiguredServiceMetadataAtUtcClockInstant() {
        var properties = new ApplicationProperties(
                "ai-quant-research-api",
                "0.1.0-test",
                DataMode.MOCK
        );
        var service = new HealthService(properties, Clock.fixed(NOW, ZoneOffset.UTC));

        var health = service.currentHealth();

        assertThat(health.service()).isEqualTo("ai-quant-research-api");
        assertThat(health.status()).isEqualTo(ServiceStatus.UP);
        assertThat(health.version()).isEqualTo("0.1.0-test");
        assertThat(health.timestamp()).isEqualTo(NOW);
        assertThat(health.dataMode()).isEqualTo(DataMode.MOCK);
    }
}
