package com.aiquantresearch.api.research.llm;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public record PreparedLlmRequest(
        String instructions,
        ArrayNode input,
        ObjectNode schema,
        ArrayNode tools,
        String requestHash,
        String safetyIdentifier,
        String promptCacheKey,
        String evidencePackHash,
        int inputUtf8Bytes
) {
}
