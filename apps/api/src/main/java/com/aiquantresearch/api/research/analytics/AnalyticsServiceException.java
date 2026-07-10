package com.aiquantresearch.api.research.analytics;

public class AnalyticsServiceException extends RuntimeException {

    private final boolean retryable;

    public AnalyticsServiceException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public AnalyticsServiceException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
