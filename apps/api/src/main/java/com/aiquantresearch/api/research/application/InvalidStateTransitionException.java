package com.aiquantresearch.api.research.application;

public final class InvalidStateTransitionException extends ResearchApplicationException {

    public InvalidStateTransitionException(String message) {
        super("INVALID_STATE_TRANSITION", message, false);
    }
}
