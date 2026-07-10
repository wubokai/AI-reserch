package com.aiquantresearch.api.health.web;

import com.aiquantresearch.api.health.application.HealthComponentSnapshot;
import com.aiquantresearch.api.health.domain.ServiceStatus;

public record HealthComponentResponse(
        ServiceStatus status,
        boolean critical,
        long latencyMs,
        String message
) {
    static HealthComponentResponse from(HealthComponentSnapshot component) {
        return new HealthComponentResponse(
                component.status(),
                component.critical(),
                component.latencyMs(),
                component.message()
        );
    }
}
