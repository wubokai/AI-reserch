package com.aiquantresearch.api.research.domain;

import java.util.Arrays;

/** The eleven durable workflow steps exposed by the v1 API contract. */
public enum StepType {
    RESOLVE_SECURITY(1, ResearchStatus.RESOLVING_SECURITY, 5),
    FETCH_MARKET_DATA(2, ResearchStatus.FETCHING_MARKET_DATA, 15),
    FETCH_FUNDAMENTALS(3, ResearchStatus.FETCHING_FUNDAMENTALS, 25),
    FETCH_FILINGS(4, ResearchStatus.FETCHING_FILINGS, 35),
    FETCH_MACRO_DATA(5, ResearchStatus.FETCHING_MACRO_DATA, 45),
    VALIDATE_DATA(6, ResearchStatus.VALIDATING_DATA, 55),
    RUN_QUANT_ANALYSIS(7, ResearchStatus.RUNNING_QUANT_ANALYSIS, 65),
    ANALYZE_FUNDAMENTALS(8, ResearchStatus.ANALYZING_FUNDAMENTALS, 75),
    BUILD_EVIDENCE(9, ResearchStatus.BUILDING_EVIDENCE, 82),
    GENERATE_REPORT(10, ResearchStatus.GENERATING_REPORT, 90),
    VALIDATE_REPORT(11, ResearchStatus.VALIDATING_REPORT, 96);

    private final short sequence;
    private final ResearchStatus publicStatus;
    private final short progress;

    StepType(int sequence, ResearchStatus publicStatus, int progress) {
        this.sequence = (short) sequence;
        this.publicStatus = publicStatus;
        this.progress = (short) progress;
    }

    public short sequence() {
        return sequence;
    }

    public ResearchStatus publicStatus() {
        return publicStatus;
    }

    public short progress() {
        return progress;
    }

    public static StepType atSequence(int sequence) {
        return Arrays.stream(values())
                .filter(step -> step.sequence == sequence)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown step sequence: " + sequence));
    }
}
