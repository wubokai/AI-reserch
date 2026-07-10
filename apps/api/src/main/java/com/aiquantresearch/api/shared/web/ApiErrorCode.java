package com.aiquantresearch.api.shared.web;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;

public enum ApiErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request", false),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Unauthorized", false),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden", false),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "Account disabled", false),
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "Route not found", false),
    RESEARCH_NOT_FOUND(HttpStatus.NOT_FOUND, "Research not found", false),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Invalid state transition", false),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "Idempotency key reused", false),
    IDEMPOTENCY_REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "Idempotent request in progress", true),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Idempotency conflict", false),
    OPTIMISTIC_LOCK_CONFLICT(HttpStatus.CONFLICT, "Concurrent update conflict", true),
    STALE_LEASE(HttpStatus.CONFLICT, "Stale worker lease", false),
    INVALID_SYMBOL(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid security symbol", false),
    SECURITY_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "Security mismatch", false),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable", true),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", false);

    private static final String PROBLEM_BASE = "https://api.aiquant.local/problems/";

    private final HttpStatus status;
    private final String title;
    private final boolean retryable;

    ApiErrorCode(HttpStatus status, String title, boolean retryable) {
        this.status = status;
        this.title = title;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public boolean retryable() {
        return retryable;
    }

    public URI problemType() {
        return URI.create(PROBLEM_BASE + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
