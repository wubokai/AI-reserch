package com.aiquantresearch.api.research.application;

public final class InvalidResearchRequestException extends ResearchApplicationException {

    public InvalidResearchRequestException(String message) {
        super("INVALID_REQUEST", message, false);
    }
}
