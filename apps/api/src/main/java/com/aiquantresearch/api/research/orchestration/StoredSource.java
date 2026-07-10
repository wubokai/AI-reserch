package com.aiquantresearch.api.research.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record StoredSource(
        UUID id,
        String purpose,
        String externalSourceId,
        JsonNode payload,
        String contentHash
) {
}
