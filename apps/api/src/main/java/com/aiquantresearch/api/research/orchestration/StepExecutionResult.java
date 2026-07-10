package com.aiquantresearch.api.research.orchestration;

import com.aiquantresearch.api.research.llm.LlmCallAudit;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record StepExecutionResult(
        JsonNode payload,
        boolean partial,
        List<String> warnings,
        LlmCallAudit llmCallAudit
) {
    public StepExecutionResult {
        warnings = List.copyOf(warnings);
    }

    public StepExecutionResult(JsonNode payload, boolean partial, List<String> warnings) {
        this(payload, partial, warnings, null);
    }

    public static StepExecutionResult complete(JsonNode payload) {
        return new StepExecutionResult(payload, false, List.of(), null);
    }

    public static StepExecutionResult withLlmCall(JsonNode payload, LlmCallAudit audit) {
        return new StepExecutionResult(payload, false, List.of(), audit);
    }
}
