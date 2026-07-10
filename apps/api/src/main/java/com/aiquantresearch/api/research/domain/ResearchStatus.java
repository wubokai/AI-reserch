package com.aiquantresearch.api.research.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Canonical public lifecycle for a research job.
 *
 * <p>Ordinary workflow transitions are deliberately strict. Manual retry is an
 * explicit application operation and therefore does not use {@link #canTransitionTo}.
 */
public enum ResearchStatus {
    CREATED,
    QUEUED,
    RESOLVING_SECURITY,
    FETCHING_MARKET_DATA,
    FETCHING_FUNDAMENTALS,
    FETCHING_FILINGS,
    FETCHING_MACRO_DATA,
    VALIDATING_DATA,
    RUNNING_QUANT_ANALYSIS,
    ANALYZING_FUNDAMENTALS,
    BUILDING_EVIDENCE,
    GENERATING_REPORT,
    VALIDATING_REPORT,
    COMPLETED,
    PARTIALLY_COMPLETED,
    FAILED,
    CANCELLED;

    private static final Set<ResearchStatus> TERMINAL = EnumSet.of(
            COMPLETED,
            PARTIALLY_COMPLETED,
            FAILED,
            CANCELLED
    );

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean canTransitionTo(ResearchStatus target) {
        if (target == null || this == target) {
            return target == this;
        }
        if (isTerminal()) {
            return false;
        }
        if (target == CANCELLED || target == FAILED) {
            return true;
        }
        if (this == VALIDATING_REPORT) {
            return target == COMPLETED || target == PARTIALLY_COMPLETED;
        }
        return ordinal() + 1 == target.ordinal() && !target.isTerminal();
    }

    public boolean acceptsManualRetry() {
        return this == FAILED || this == PARTIALLY_COMPLETED;
    }
}
