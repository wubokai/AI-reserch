package com.aiquantresearch.api.health.application;

import com.aiquantresearch.api.health.domain.ServiceStatus;
import com.aiquantresearch.api.shared.config.ApplicationProperties;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class HealthService {

    private static final Logger LOGGER = LoggerFactory.getLogger(HealthService.class);

    private final ApplicationProperties applicationProperties;
    private final Clock clock;
    private final List<HealthProbe> probes;
    private final LongSupplier nanoTime;

    @Autowired
    public HealthService(
            ApplicationProperties applicationProperties,
            Clock clock,
            List<HealthProbe> probes
    ) {
        this(applicationProperties, clock, probes, System::nanoTime);
    }

    HealthService(
            ApplicationProperties applicationProperties,
            Clock clock,
            List<HealthProbe> probes,
            LongSupplier nanoTime
    ) {
        this.applicationProperties = applicationProperties;
        this.clock = clock;
        this.probes = List.copyOf(probes);
        this.nanoTime = nanoTime;
    }

    public HealthSnapshot currentHealth() {
        Map<String, HealthComponentSnapshot> components = new LinkedHashMap<>();
        for (HealthProbe probe : probes) {
            components.put(probe.componentName(), execute(probe));
        }
        return new HealthSnapshot(
                aggregate(components),
                applicationProperties.version(),
                applicationProperties.dataMode(),
                clock.instant(),
                Collections.unmodifiableMap(components)
        );
    }

    private HealthComponentSnapshot execute(HealthProbe probe) {
        long startedAt = nanoTime.getAsLong();
        try {
            String message = probe.probe();
            return new HealthComponentSnapshot(
                    ServiceStatus.UP,
                    probe.critical(),
                    elapsedMillis(startedAt),
                    message
            );
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Health probe {} failed ({})",
                    probe.componentName(),
                    exception.getClass().getSimpleName()
            );
            return new HealthComponentSnapshot(
                    ServiceStatus.DOWN,
                    probe.critical(),
                    elapsedMillis(startedAt),
                    "Component unavailable"
            );
        }
    }

    private long elapsedMillis(long startedAt) {
        long elapsedNanos = Math.max(0L, nanoTime.getAsLong() - startedAt);
        return TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
    }

    private static ServiceStatus aggregate(Map<String, HealthComponentSnapshot> components) {
        boolean criticalDown = components.values().stream()
                .anyMatch(component -> component.critical()
                        && component.status() == ServiceStatus.DOWN);
        if (criticalDown) {
            return ServiceStatus.DOWN;
        }
        boolean degraded = components.values().stream()
                .anyMatch(component -> component.status() != ServiceStatus.UP);
        return degraded ? ServiceStatus.DEGRADED : ServiceStatus.UP;
    }
}
