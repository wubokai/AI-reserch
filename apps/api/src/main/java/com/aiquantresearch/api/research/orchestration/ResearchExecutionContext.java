package com.aiquantresearch.api.research.orchestration;

import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record ResearchExecutionContext(
        UUID researchId,
        UUID ownerId,
        String symbol,
        String securityType,
        String locale,
        DataMode dataMode,
        JsonNode request
) {
}
