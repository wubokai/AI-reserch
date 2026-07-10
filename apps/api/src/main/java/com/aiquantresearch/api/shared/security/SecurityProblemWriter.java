package com.aiquantresearch.api.shared.security;

import com.aiquantresearch.api.shared.web.ApiErrorCode;
import com.aiquantresearch.api.shared.web.ApiErrorResponse;
import com.aiquantresearch.api.shared.web.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.MediaType;

public class SecurityProblemWriter {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    SecurityProblemWriter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    void write(HttpServletResponse response, ApiErrorCode code, String message) throws IOException {
        String requestId = MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY);
        if (requestId == null) {
            requestId = "req_unavailable";
        }
        ApiErrorResponse body = new ApiErrorResponse(
                code.problemType(),
                code.title(),
                clock.instant(),
                code.status().value(),
                code.name(),
                message,
                requestId,
                null,
                code.retryable(),
                Map.of()
        );
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
