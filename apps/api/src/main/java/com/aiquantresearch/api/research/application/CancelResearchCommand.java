package com.aiquantresearch.api.research.application;

public record CancelResearchCommand(String reason) {

    public CancelResearchCommand {
        reason = SafeReasonNormalizer.nullable(reason);
    }

    public static CancelResearchCommand withoutReason() {
        return new CancelResearchCommand(null);
    }
}
