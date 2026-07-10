package com.aiquantresearch.api.health.application;

import com.aiquantresearch.api.health.domain.ServiceStatus;
import com.aiquantresearch.api.shared.domain.DataMode;
import java.time.Instant;

public record HealthSnapshot(
        String service,
        ServiceStatus status,
        String version,
        Instant timestamp,
        DataMode dataMode
) {
}
