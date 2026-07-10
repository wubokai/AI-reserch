package com.aiquantresearch.api.research.application;

public final class SecurityMismatchException extends ResearchApplicationException {

    public SecurityMismatchException(String message) {
        super("SECURITY_MISMATCH", message, false);
    }
}
