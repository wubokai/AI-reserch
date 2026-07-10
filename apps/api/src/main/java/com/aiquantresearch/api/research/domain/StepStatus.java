package com.aiquantresearch.api.research.domain;

public enum StepStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED || this == CANCELLED;
    }

    public boolean satisfiesDependency() {
        return this == SUCCEEDED || this == SKIPPED;
    }
}
