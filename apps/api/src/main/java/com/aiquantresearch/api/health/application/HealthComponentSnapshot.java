package com.aiquantresearch.api.health.application;

import com.aiquantresearch.api.health.domain.ServiceStatus;

public record HealthComponentSnapshot(
        ServiceStatus status,
        boolean critical,
        long latencyMs,
        String message
) {
}
