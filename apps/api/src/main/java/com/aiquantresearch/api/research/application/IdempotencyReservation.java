package com.aiquantresearch.api.research.application;

import java.util.UUID;

public record IdempotencyReservation(
        UUID recordId,
        boolean replayed,
        int responseStatus,
        String responseBody,
        UUID resourceId
) {

    public static IdempotencyReservation acquired(UUID recordId) {
        return new IdempotencyReservation(recordId, false, 0, null, null);
    }

    public static IdempotencyReservation replay(
            UUID recordId,
            int responseStatus,
            String responseBody,
            UUID resourceId
    ) {
        return new IdempotencyReservation(
                recordId,
                true,
                responseStatus,
                responseBody,
                resourceId
        );
    }
}
