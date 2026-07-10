package com.aiquantresearch.api.health.application;

import com.aiquantresearch.api.health.domain.ServiceStatus;
import com.aiquantresearch.api.shared.domain.DataMode;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record HealthSnapshot(
        ServiceStatus status,
        String version,
        DataMode dataMode,
        Instant timestamp,
        Map<String, HealthComponentSnapshot> components
) {
    public HealthSnapshot {
        components = Collections.unmodifiableMap(new LinkedHashMap<>(components));
    }
}
