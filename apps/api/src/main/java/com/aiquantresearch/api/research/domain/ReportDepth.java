package com.aiquantresearch.api.research.domain;

public enum ReportDepth {
    QUICK(1, 40, 80, 0),
    STANDARD(2, 80, 140, 1),
    DEEP(6, 120, 180, 8);

    private final int maxFilings;
    private final int maxEvidenceItems;
    private final int maxCalculations;
    private final int maxToolRounds;

    ReportDepth(
            int maxFilings,
            int maxEvidenceItems,
            int maxCalculations,
            int maxToolRounds
    ) {
        this.maxFilings = maxFilings;
        this.maxEvidenceItems = maxEvidenceItems;
        this.maxCalculations = maxCalculations;
        this.maxToolRounds = maxToolRounds;
    }

    public int maxFilings() {
        return maxFilings;
    }

    public int maxEvidenceItems() {
        return maxEvidenceItems;
    }

    public int maxCalculations() {
        return maxCalculations;
    }

    public int maxToolRounds(int configuredMaximum) {
        return Math.min(maxToolRounds, configuredMaximum);
    }

    public static ReportDepth fromRequestValue(String value) {
        if (value == null || value.isBlank()) {
            return STANDARD;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            return STANDARD;
        }
    }
}
