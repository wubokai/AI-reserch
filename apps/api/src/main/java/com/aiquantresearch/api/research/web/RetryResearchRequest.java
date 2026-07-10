package com.aiquantresearch.api.research.web;

import com.aiquantresearch.api.research.application.RetryResearchCommand;
import com.aiquantresearch.api.research.domain.StepType;
import jakarta.validation.constraints.Size;

public record RetryResearchRequest(
        StepType fromStep,
        @Size(max = 500) String reason
) {
    public RetryResearchCommand toCommand() {
        return new RetryResearchCommand(fromStep, reason);
    }
}
