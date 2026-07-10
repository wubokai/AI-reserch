package com.aiquantresearch.api.research.worker;

import com.aiquantresearch.api.research.domain.StepType;
import java.util.UUID;

public record QueueCompletion(
        String resultCode,
        UUID researchJobId,
        UUID researchStepId,
        String committedOutputHash,
        UUID nextResearchStepId,
        StepType nextStepType,
        String nextInputHash
) {
    public boolean committed() {
        return resultCode != null && (
                resultCode.equals("SUCCEEDED_AND_ADVANCED")
                || resultCode.equals("ALREADY_SUCCEEDED_AND_ADVANCED")
                || resultCode.equals("ALREADY_ADVANCED")
                || resultCode.equals("SUCCEEDED_NO_SUCCESSOR")
                || resultCode.equals("ALREADY_SUCCEEDED_NO_SUCCESSOR")
        );
    }
}
