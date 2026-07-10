package com.aiquantresearch.api.research.llm;

import com.aiquantresearch.api.research.orchestration.ResearchExecutionContext;
import com.aiquantresearch.api.research.orchestration.StoredEvidence;
import com.aiquantresearch.api.research.orchestration.StoredQuantResult;
import com.aiquantresearch.api.research.orchestration.StoredSource;
import java.util.List;
import java.util.UUID;

public record ResearchLanguageModelRequest(
        UUID attemptId,
        ResearchExecutionContext context,
        List<StoredSource> sources,
        List<StoredQuantResult> quantResults,
        List<StoredEvidence> evidence,
        int reportVersion
) {

    public ResearchLanguageModelRequest {
        sources = List.copyOf(sources);
        quantResults = List.copyOf(quantResults);
        evidence = List.copyOf(evidence);
        if (attemptId == null || context == null || reportVersion < 1) {
            throw new IllegalArgumentException("LLM request identity is invalid");
        }
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("LLM request requires an Evidence allowlist");
        }
    }
}
