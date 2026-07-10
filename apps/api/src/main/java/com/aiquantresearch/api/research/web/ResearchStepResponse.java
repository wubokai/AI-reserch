package com.aiquantresearch.api.research.web;

import com.aiquantresearch.api.research.application.ResearchStepView;
import com.aiquantresearch.api.research.domain.StepStatus;
import com.aiquantresearch.api.research.domain.StepType;
import java.time.Instant;

public record ResearchStepResponse(
        StepType step,
        StepStatus status,
        int attemptCount,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        boolean retryable,
        ErrorSummaryResponse error
) {
    static ResearchStepResponse from(ResearchStepView view) {
        return new ResearchStepResponse(
                view.step(),
                view.status(),
                view.attemptCount(),
                view.startedAt(),
                view.completedAt(),
                view.durationMs(),
                view.retryable(),
                ErrorSummaryResponse.from(view.error())
        );
    }
}
