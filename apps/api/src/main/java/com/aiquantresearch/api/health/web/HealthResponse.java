package com.aiquantresearch.api.health.web;

import com.aiquantresearch.api.health.application.HealthSnapshot;
import com.aiquantresearch.api.health.domain.ServiceStatus;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;

@JsonPropertyOrder({"service", "status", "version", "timestamp", "dataMode"})
public record HealthResponse(
        String service,
        ServiceStatus status,
        String version,
        Instant timestamp,
        DataMode dataMode
) {
    static HealthResponse from(HealthSnapshot snapshot) {
        return new HealthResponse(
                snapshot.service(),
                snapshot.status(),
                snapshot.version(),
                snapshot.timestamp(),
                snapshot.dataMode()
        );
    }
}
