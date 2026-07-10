package com.aiquantresearch.api.research.application;

import com.aiquantresearch.api.research.domain.ReportDepth;
import com.aiquantresearch.api.research.domain.ResearchStatus;
import com.aiquantresearch.api.shared.domain.DataMode;
import java.time.Instant;
import java.util.UUID;

public record ResearchItemView(
        UUID researchId,
        String title,
        String query,
        String symbol,
        String companyName,
        String benchmark,
        ResearchStatus status,
        int progress,
        ReportDepth reportDepth,
        DataMode dataMode,
        Integer latestReportVersion,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
