package com.aiquantresearch.api.research.worker;

public class StepExecutionException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public StepExecutionException(String code, String safeMessage, boolean retryable) {
        super(safeMessage);
        this.code = code;
        this.retryable = retryable;
    }

    public StepExecutionException(
            String code,
            String safeMessage,
            boolean retryable,
            Throwable cause
    ) {
        super(safeMessage, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
