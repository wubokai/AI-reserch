package com.aiquantresearch.api.research.application;

import com.aiquantresearch.api.research.domain.StepType;

public record ErrorSummaryView(
        String code,
        String message,
        boolean retryable,
        StepType failedStep
) {
}
