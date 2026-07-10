package com.aiquantresearch.api.research.application;

public final class InvalidSymbolException extends ResearchApplicationException {

    public InvalidSymbolException(String message) {
        super("INVALID_SYMBOL", message, false);
    }
}
