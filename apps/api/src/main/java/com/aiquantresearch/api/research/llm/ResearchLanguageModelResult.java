package com.aiquantresearch.api.research.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record ResearchLanguageModelResult(
        JsonNode report,
        LlmCallAudit audit,
        boolean partial,
        List<String> warnings
) {

    public ResearchLanguageModelResult {
        if (report == null || !report.isObject() || audit == null) {
            throw new IllegalArgumentException("LLM result must contain a report and audit");
        }
        warnings = List.copyOf(warnings);
    }

    public ResearchLanguageModelResult(JsonNode report, LlmCallAudit audit) {
        this(report, audit, false, List.of());
    }
}
