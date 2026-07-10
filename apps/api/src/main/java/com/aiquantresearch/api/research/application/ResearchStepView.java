package com.aiquantresearch.api.research.application;

import com.aiquantresearch.api.research.domain.StepStatus;
import com.aiquantresearch.api.research.domain.StepType;
import java.time.Instant;

public record ResearchStepView(
        StepType step,
        StepStatus status,
        int attemptCount,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        boolean retryable,
        ErrorSummaryView error
) {
}
