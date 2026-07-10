package com.aiquantresearch.api.research.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record StoredSource(
        UUID id,
        String purpose,
        String externalSourceId,
        JsonNode payload,
        String contentHash,
        String provider,
        boolean primarySource,
        String freshnessStatus,
        boolean demoData
) {

    public StoredSource(
            UUID id,
            String purpose,
            String externalSourceId,
            JsonNode payload,
            String contentHash
    ) {
        this(id, purpose, externalSourceId, payload, contentHash,
                "MOCK_FIXTURE", true, "FRESH", true);
    }
}
