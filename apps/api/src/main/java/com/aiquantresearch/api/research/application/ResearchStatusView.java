package com.aiquantresearch.api.research.application;

import com.aiquantresearch.api.research.domain.ResearchStatus;
import com.aiquantresearch.api.research.domain.StepType;
import com.aiquantresearch.api.shared.domain.DataMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ResearchStatusView(
        UUID researchId,
        ResearchStatus status,
        int progress,
        StepType currentStep,
        int completedSteps,
        int totalSteps,
        boolean cancellationRequested,
        DataMode dataMode,
        ErrorSummaryView error,
        List<ResearchStepView> steps,
        Instant updatedAt
) {

    public ResearchStatusView {
        steps = List.copyOf(steps);
    }
}
