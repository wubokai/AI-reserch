package com.aiquantresearch.api.research.web;

import com.aiquantresearch.api.research.application.ResearchDetailView;
import com.aiquantresearch.api.research.domain.ReportDepth;
import com.aiquantresearch.api.research.domain.ResearchStatus;
import com.aiquantresearch.api.research.domain.StepType;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ResearchDetailResponse(
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
        Instant completedAt,
        CreateResearchRequest request,
        @JsonInclude(JsonInclude.Include.ALWAYS) StepType currentStep,
        Instant startedAt,
        boolean cancellationRequested,
        ErrorSummaryResponse lastError,
        Object latestReport,
        List<WarningResponse> warnings,
        ResearchLinksResponse links
) {
    static ResearchDetailResponse from(ResearchDetailView view) {
        ResearchItemResponse item = ResearchItemResponse.from(view.research());
        return new ResearchDetailResponse(
                item.researchId(),
                item.title(),
                item.query(),
                item.symbol(),
                item.companyName(),
                item.benchmark(),
                item.status(),
                item.progress(),
                item.reportDepth(),
                item.dataMode(),
                item.latestReportVersion(),
                item.createdAt(),
                item.updatedAt(),
                item.completedAt(),
                CreateResearchRequest.fromCommand(view.request()),
                view.currentStep(),
                view.startedAt(),
                view.cancellationRequested(),
                ErrorSummaryResponse.from(view.lastError()),
                null,
                view.warnings().stream().map(WarningResponse::fromMessage).toList(),
                ResearchLinksResponse.forResearch(item.researchId())
        );
    }
}
