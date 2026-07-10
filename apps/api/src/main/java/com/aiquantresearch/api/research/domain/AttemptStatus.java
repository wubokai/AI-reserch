package com.aiquantresearch.api.research.domain;

public enum AttemptStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    LEASE_EXPIRED;

    public boolean isTerminal() {
        return this != RUNNING;
    }
}
