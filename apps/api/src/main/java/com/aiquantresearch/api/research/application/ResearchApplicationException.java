package com.aiquantresearch.api.research.application;

import java.util.UUID;

public class ResearchApplicationException extends RuntimeException {

    private final String code;
    private final boolean retryable;
    private final UUID researchId;

    public ResearchApplicationException(String code, String message, boolean retryable) {
        this(code, message, retryable, null);
    }

    public ResearchApplicationException(
            String code,
            String message,
            boolean retryable,
            UUID researchId
    ) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.researchId = researchId;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public UUID researchId() {
        return researchId;
    }
}
