package com.aiquantresearch.api.research.application;

public final class IdempotencyKeyReusedException extends ResearchApplicationException {

    public IdempotencyKeyReusedException() {
        super(
                "IDEMPOTENCY_KEY_REUSED",
                "The Idempotency-Key was already used with a different request",
                false
        );
    }
}
