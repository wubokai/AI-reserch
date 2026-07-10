package com.aiquantresearch.api.shared.web;

import java.util.Map;
import java.util.UUID;

public class ApiException extends RuntimeException {

    private final ApiErrorCode errorCode;
    private final UUID researchId;
    private final Map<String, Object> details;

    public ApiException(ApiErrorCode errorCode, String message) {
        this(errorCode, message, null, Map.of());
    }

    public ApiException(
            ApiErrorCode errorCode,
            String message,
            UUID researchId,
            Map<String, Object> details
    ) {
        super(message);
        this.errorCode = errorCode;
        this.researchId = researchId;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ApiErrorCode errorCode() {
        return errorCode;
    }

    public UUID researchId() {
        return researchId;
    }

    public Map<String, Object> details() {
        return details;
    }
}
