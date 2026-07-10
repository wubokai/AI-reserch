package com.aiquantresearch.api.research.application;

import java.util.UUID;

public record IdempotencyScope(
        UUID ownerId,
        String httpMethod,
        String requestPath,
        String idempotencyKey,
        String requestHash
) {
}
