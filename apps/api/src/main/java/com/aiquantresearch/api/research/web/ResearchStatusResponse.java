package com.aiquantresearch.api.research.web;

import com.aiquantresearch.api.research.application.ResearchStatusView;
import com.aiquantresearch.api.research.domain.ResearchStatus;
import com.aiquantresearch.api.research.domain.StepType;
import com.aiquantresearch.api.shared.domain.DataMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ResearchStatusResponse(
        UUID researchId,
        ResearchStatus status,
        int progress,
        StepType currentStep,
        int completedSteps,
        int totalSteps,
        boolean cancellationRequested,
        DataMode dataMode,
        ErrorSummaryResponse error,
        List<ResearchStepResponse> steps,
        Instant updatedAt
) {
    static ResearchStatusResponse from(ResearchStatusView view) {
        return new ResearchStatusResponse(
                view.researchId(),
                view.status(),
                view.progress(),
                view.currentStep(),
                view.completedSteps(),
                view.totalSteps(),
                view.cancellationRequested(),
                view.dataMode(),
                ErrorSummaryResponse.from(view.error()),
                view.steps().stream().map(ResearchStepResponse::from).toList(),
                view.updatedAt()
        );
    }
}
