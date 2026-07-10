package com.aiquantresearch.api.research.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record StepExecutionResult(
        JsonNode payload,
        boolean partial,
        List<String> warnings
) {
    public StepExecutionResult {
        warnings = List.copyOf(warnings);
    }

    public static StepExecutionResult complete(JsonNode payload) {
        return new StepExecutionResult(payload, false, List.of());
    }
}
