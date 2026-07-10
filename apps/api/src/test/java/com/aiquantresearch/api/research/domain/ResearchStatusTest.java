package com.aiquantresearch.api.research.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResearchStatusTest {

    @Test
    void evaluatesEveryLegalAndIllegalTransitionInTheCanonicalStateMatrix() {
        Map<ResearchStatus, Set<ResearchStatus>> expected = expectedTransitions();

        for (ResearchStatus source : ResearchStatus.values()) {
            assertThat(source.canTransitionTo(null))
                    .as("%s -> null", source)
                    .isFalse();
            for (ResearchStatus target : ResearchStatus.values()) {
                assertThat(source.canTransitionTo(target))
                        .as("%s -> %s", source, target)
                        .isEqualTo(expected.get(source).contains(target));
            }
        }
    }

    @Test
    void terminalAndManualRetryClassificationsCoverEveryStatus() {
        Set<ResearchStatus> terminal = EnumSet.of(
                ResearchStatus.COMPLETED,
                ResearchStatus.PARTIALLY_COMPLETED,
                ResearchStatus.FAILED,
                ResearchStatus.CANCELLED
        );
        Set<ResearchStatus> retryable = EnumSet.of(
                ResearchStatus.PARTIALLY_COMPLETED,
                ResearchStatus.FAILED
        );

        for (ResearchStatus status : ResearchStatus.values()) {
            assertThat(status.isTerminal())
                    .as("terminal classification for %s", status)
                    .isEqualTo(terminal.contains(status));
            assertThat(status.acceptsManualRetry())
                    .as("manual retry classification for %s", status)
                    .isEqualTo(retryable.contains(status));
        }
    }

    private static Map<ResearchStatus, Set<ResearchStatus>> expectedTransitions() {
        Map<ResearchStatus, Set<ResearchStatus>> transitions =
                new EnumMap<>(ResearchStatus.class);
        transitions.put(ResearchStatus.CREATED, legal(
                ResearchStatus.CREATED,
                ResearchStatus.QUEUED,
                ResearchStatus.FAILED,
                ResearchStatus.CANCELLED
        ));
        transitions.put(ResearchStatus.QUEUED, legal(
                ResearchStatus.QUEUED,
                ResearchStatus.RESOLVING_SECURITY,
                ResearchStatus.FAILED,
                ResearchStatus.CANCELLED
        ));
        transitions.put(ResearchStatus.RESOLVING_SECURITY, legal(
                ResearchStatus.RESOLVING_SECURITY,
                ResearchStatus.FETCHING_MARKET_DATA,
                ResearchStatus.FAILED,
                ResearchStatus.CANCELLED
        ));
        transitions.put(ResearchStatus.FETCHING_MARKET_DATA, legal(
                ResearchStatus.FETCHING_MARKET_DATA,
                ResearchStatus.FETCHING_FUNDAMENTALS,
                ResearchStatus.FAILED,
                ResearchStatus.CANCELLED
        ));
        transitions.put(ResearchStatus.FETCHING_FUNDAMENTALS, legal(
                ResearchStatus.FETCHING_FUNDAMENTALS,
                ResearchStatus.FETCHING_FILINGS,
                ResearchStatus.FAILED,
                ResearchStatus.CANCELLED
        ));
        transitions.put(ResearchStatus.FETCHING_FILINGS, legal(
                ResearchStatus.FETCHING_FILINGS,
                ResearchStatus.FETCHING_MACRO_DATA,
                ResearchStatus.FAILED,
                ResearchStatus.CANCELLED
        ));
        transitions.put(ResearchStatus.FETCHING_MACRO_DATA, legal(
                ResearchStatus.FETCHING_MACRO_DATA,
                ResearchStatus.VALIDATING_DATA,
                ResearchStatus.FAILED,
                ResearchStatus.CANCELLED
        ));
        transitions.put(ResearchStatus.VALIDATING_DATA, legal(
                ResearchStatus.VALIDATING_DATA,
                ResearchStatus.RUNNING_QUANT_ANALYSIS,
                ResearchStatus.FAILED,
                ResearchStatus.CANCELLED
        ));
        transitions.put(ResearchStatus.RUNNING_QUANT_ANALYSIS, legal(
                ResearchStatus.RUNNING_QUANT_ANALYSIS,
                ResearchStatus.ANALYZING_FUNDAMENTALS,
                ResearchStatus.FAILED,
                ResearchStatus.CANCELLED
        ));
        transitions.put(ResearchStatus.ANALYZING_FUNDAMENTALS, legal(
                ResearchStatus.ANALYZING_FUNDAMENTALS,
                ResearchStatus.BUILDING_EVIDENCE,
                ResearchStatus.FAILED,
                ResearchStatus.CANCELLED
        ));
        transitions.put(ResearchStatus.BUILDING_EVIDENCE, legal(
                ResearchStatus.BUILDING_EVIDENCE,
                ResearchStatus.GENERATING_REPORT,
                ResearchStatus.FAILED,
                ResearchStatus.CANCELLED
        ));
        transitions.put(ResearchStatus.GENERATING_REPORT, legal(
                ResearchStatus.GENERATING_REPORT,
                ResearchStatus.VALIDATING_REPORT,
                ResearchStatus.FAILED,
                ResearchStatus.CANCELLED
        ));
        transitions.put(ResearchStatus.VALIDATING_REPORT, legal(
                ResearchStatus.VALIDATING_REPORT,
                ResearchStatus.COMPLETED,
                ResearchStatus.PARTIALLY_COMPLETED,
                ResearchStatus.FAILED,
                ResearchStatus.CANCELLED
        ));
        transitions.put(ResearchStatus.COMPLETED, legal(ResearchStatus.COMPLETED));
        transitions.put(
                ResearchStatus.PARTIALLY_COMPLETED,
                legal(ResearchStatus.PARTIALLY_COMPLETED)
        );
        transitions.put(ResearchStatus.FAILED, legal(ResearchStatus.FAILED));
        transitions.put(ResearchStatus.CANCELLED, legal(ResearchStatus.CANCELLED));
        return transitions;
    }

    private static Set<ResearchStatus> legal(
            ResearchStatus first,
            ResearchStatus... remaining
    ) {
        return EnumSet.of(first, remaining);
    }
}
