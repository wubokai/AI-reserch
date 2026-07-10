package com.aiquantresearch.api.research.application;

import com.aiquantresearch.api.research.domain.ResearchStatus;
import com.aiquantresearch.api.shared.domain.DataMode;
import java.time.Instant;
import java.util.UUID;

public record ResearchAcceptedView(
        UUID researchId,
        ResearchStatus status,
        DataMode dataMode,
        Instant createdAt
) {
}
