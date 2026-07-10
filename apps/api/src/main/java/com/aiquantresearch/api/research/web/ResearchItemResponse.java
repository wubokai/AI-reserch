package com.aiquantresearch.api.research.web;

import com.aiquantresearch.api.research.application.ResearchItemView;
import com.aiquantresearch.api.research.domain.ReportDepth;
import com.aiquantresearch.api.research.domain.ResearchStatus;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

public record ResearchItemResponse(
        UUID researchId,
        String title,
        String query,
        @JsonInclude(JsonInclude.Include.ALWAYS) String symbol,
        @JsonInclude(JsonInclude.Include.ALWAYS) String companyName,
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
    static ResearchItemResponse from(ResearchItemView view) {
        return new ResearchItemResponse(
                view.researchId(),
                view.title(),
                view.query(),
                view.symbol(),
                view.companyName(),
                view.benchmark(),
                view.status(),
                view.progress(),
                view.reportDepth(),
                view.dataMode(),
                view.latestReportVersion(),
                view.createdAt(),
                view.updatedAt(),
                view.completedAt()
        );
    }
}
