package com.aiquantresearch.api.health.application;

import com.aiquantresearch.api.health.domain.ServiceStatus;
import com.aiquantresearch.api.shared.config.ApplicationProperties;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class HealthService {

    private final ApplicationProperties applicationProperties;
    private final Clock clock;

    public HealthService(ApplicationProperties applicationProperties, Clock clock) {
        this.applicationProperties = applicationProperties;
        this.clock = clock;
    }

    public HealthSnapshot currentHealth() {
        return new HealthSnapshot(
                applicationProperties.serviceName(),
                ServiceStatus.UP,
                applicationProperties.version(),
                clock.instant(),
                applicationProperties.dataMode()
        );
    }
}
