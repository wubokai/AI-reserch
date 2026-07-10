package com.aiquantresearch.api.research.web;

import com.aiquantresearch.api.research.application.ErrorSummaryView;
import com.aiquantresearch.api.research.domain.StepType;

public record ErrorSummaryResponse(
        String code,
        String message,
        boolean retryable,
        StepType failedStep
) {
    static ErrorSummaryResponse from(ErrorSummaryView view) {
        return view == null
                ? null
                : new ErrorSummaryResponse(
                        view.code(),
                        view.message(),
                        view.retryable(),
                        view.failedStep()
                );
    }
}
