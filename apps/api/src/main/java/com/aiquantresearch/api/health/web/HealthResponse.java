package com.aiquantresearch.api.health.web;

import com.aiquantresearch.api.health.application.HealthSnapshot;
import com.aiquantresearch.api.health.domain.ServiceStatus;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonPropertyOrder({"status", "version", "dataMode", "timestamp", "components"})
public record HealthResponse(
        ServiceStatus status,
        String version,
        DataMode dataMode,
        Instant timestamp,
        Map<String, HealthComponentResponse> components
) {
    public HealthResponse {
        components = Collections.unmodifiableMap(new LinkedHashMap<>(components));
    }

    static HealthResponse from(HealthSnapshot snapshot) {
        Map<String, HealthComponentResponse> components = new LinkedHashMap<>();
        snapshot.components().forEach((name, component) ->
                components.put(name, HealthComponentResponse.from(component))
        );
        return new HealthResponse(
                snapshot.status(),
                snapshot.version(),
                snapshot.dataMode(),
                snapshot.timestamp(),
                components
        );
    }
}
