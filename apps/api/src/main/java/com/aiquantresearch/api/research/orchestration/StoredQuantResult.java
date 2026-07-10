package com.aiquantresearch.api.research.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.UUID;

public record StoredQuantResult(
        UUID id,
        String publicId,
        String metricName,
        BigDecimal value,
        String unit,
        String status,
        JsonNode result
) {
}
