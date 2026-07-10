package com.aiquantresearch.api.research.worker;

import java.time.Instant;
import java.util.UUID;

public record QueueFailure(
        String resultCode,
        UUID researchJobId,
        UUID researchStepId,
        String stepStatus,
        Instant availableAt
) {
    public Disposition disposition() {
        return switch (resultCode) {
            case "RETRY_SCHEDULED" -> Disposition.RETRY_SCHEDULED;
            case "FAILED" -> Disposition.STEP_FAILED;
            case "CANCELLED" -> Disposition.CANCELLED;
            case "STALE_LEASE" -> Disposition.STALE_LEASE;
            case "RESEARCH_TERMINAL" -> Disposition.RESEARCH_TERMINAL;
            default -> throw new QueueProtocolException(
                    "fail_step returned an unknown result code: " + resultCode
            );
        };
    }

    public enum Disposition {
        RETRY_SCHEDULED,
        STEP_FAILED,
        CANCELLED,
        STALE_LEASE,
        RESEARCH_TERMINAL
    }
}
