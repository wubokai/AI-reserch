package com.aiquantresearch.api.research.application;

public final class IdempotencyRequestInProgressException extends ResearchApplicationException {

    public IdempotencyRequestInProgressException() {
        super(
                "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                "An identical request is still being processed; retry shortly",
                true
        );
    }
}
