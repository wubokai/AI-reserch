package com.aiquantresearch.api.research.application;

import java.time.Instant;
import java.util.UUID;

public interface IdempotencyBoundary {

    IdempotencyReservation reserve(IdempotencyScope scope, Instant now);

    void complete(
            UUID recordId,
            int responseStatus,
            String responseBody,
            UUID resourceId,
            Instant now
    );
}
