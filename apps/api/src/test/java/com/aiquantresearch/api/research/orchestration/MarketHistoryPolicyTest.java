package com.aiquantresearch.api.research.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MarketHistoryPolicyTest {

    @Test
    void acceptsShorterRequestedHistoryWhenTheDeterministicFloorIsMet() {
        assertThat(MarketHistoryPolicy.hasMinimumObservations(346, 346)).isTrue();
        assertThat(MarketHistoryPolicy.isShorterThanRequested(
                LocalDate.parse("2021-07-11"),
                LocalDate.parse("2025-02-24")
        )).isTrue();
    }

    @Test
    void rejectsSeriesBelowTheTwoHundredObservationFloor() {
        assertThat(MarketHistoryPolicy.hasMinimumObservations(199, 346)).isFalse();
        assertThat(MarketHistoryPolicy.hasMinimumObservations(346, 199)).isFalse();
    }

    @Test
    void recognizesCompleteRequestedCoverage() {
        LocalDate requestedStart = LocalDate.parse("2021-07-11");
        assertThat(MarketHistoryPolicy.isShorterThanRequested(
                requestedStart,
                requestedStart
        )).isFalse();
    }
}
