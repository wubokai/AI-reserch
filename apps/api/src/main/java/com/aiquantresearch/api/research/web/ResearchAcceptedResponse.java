package com.aiquantresearch.api.research.web;

import com.aiquantresearch.api.research.application.ResearchAcceptedView;
import com.aiquantresearch.api.research.domain.ResearchStatus;
import com.aiquantresearch.api.shared.domain.DataMode;
import java.time.Instant;
import java.util.UUID;

public record ResearchAcceptedResponse(
        UUID researchId,
        ResearchStatus status,
        DataMode dataMode,
        Instant createdAt,
        ResearchLinksResponse links
) {
    static ResearchAcceptedResponse from(ResearchAcceptedView view) {
        return new ResearchAcceptedResponse(
                view.researchId(),
                view.status(),
                view.dataMode(),
                view.createdAt(),
                ResearchLinksResponse.forResearch(view.researchId())
        );
    }
}
