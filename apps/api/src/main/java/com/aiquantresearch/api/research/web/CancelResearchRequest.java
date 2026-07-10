package com.aiquantresearch.api.research.web;

import com.aiquantresearch.api.research.application.CancelResearchCommand;
import jakarta.validation.constraints.Size;

public record CancelResearchRequest(@Size(max = 500) String reason) {
    public CancelResearchCommand toCommand() {
        return new CancelResearchCommand(reason);
    }
}
