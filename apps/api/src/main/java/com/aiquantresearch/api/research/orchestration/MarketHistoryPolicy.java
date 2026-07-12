package com.aiquantresearch.api.research.orchestration;

import java.time.LocalDate;

final class MarketHistoryPolicy {

    static final int MINIMUM_OBSERVATIONS = 200;

    private MarketHistoryPolicy() {
    }

    static boolean hasMinimumObservations(int targetObservations, int benchmarkObservations) {
        return targetObservations >= MINIMUM_OBSERVATIONS
                && benchmarkObservations >= MINIMUM_OBSERVATIONS;
    }

    static boolean isShorterThanRequested(LocalDate requestedStart, LocalDate effectiveStart) {
        return effectiveStart.isAfter(requestedStart);
    }
}
