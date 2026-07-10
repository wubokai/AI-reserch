package com.aiquantresearch.api.research.application;

import com.aiquantresearch.api.research.domain.StepType;
import java.time.Instant;
import java.util.List;

public record ResearchDetailView(
        ResearchItemView research,
        CreateResearchCommand request,
        StepType currentStep,
        Instant startedAt,
        boolean cancellationRequested,
        ErrorSummaryView lastError,
        List<String> warnings
) {

    public ResearchDetailView {
        warnings = List.copyOf(warnings);
    }
}
