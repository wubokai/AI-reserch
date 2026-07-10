package com.aiquantresearch.api.research.application;

import com.aiquantresearch.api.research.domain.StepType;

public record RetryResearchCommand(StepType fromStep, String reason) {

    public RetryResearchCommand {
        reason = SafeReasonNormalizer.nullable(reason);
    }

    public static RetryResearchCommand fromFirstFailedStep() {
        return new RetryResearchCommand(null, null);
    }
}
