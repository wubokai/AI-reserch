package com.aiquantresearch.api.shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@JsonPropertyOrder({
        "type", "title", "timestamp", "status", "code", "message",
        "requestId", "researchId", "retryable", "details"
})
public record ApiErrorResponse(
        URI type,
        String title,
        Instant timestamp,
        int status,
        String code,
        String message,
        String requestId,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        UUID researchId,
        boolean retryable,
        Map<String, Object> details
) {
}
