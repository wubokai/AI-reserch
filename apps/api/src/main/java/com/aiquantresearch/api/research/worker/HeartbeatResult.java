package com.aiquantresearch.api.research.worker;

import java.time.Instant;

public record HeartbeatResult(
        String resultCode,
        boolean cancellationRequested,
        Instant leaseExpiresAt
) {
    public boolean accepted() {
        return "HEARTBEAT_ACCEPTED".equals(resultCode);
    }
}
