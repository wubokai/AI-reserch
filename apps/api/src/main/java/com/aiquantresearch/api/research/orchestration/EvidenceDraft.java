package com.aiquantresearch.api.research.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record EvidenceDraft(
        String key,
        String evidenceType,
        String title,
        String summary,
        JsonNode value,
        String unit,
        UUID sourceSnapshotId,
        UUID quantResultId,
        double qualityScore
) {
}
