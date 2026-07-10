package com.aiquantresearch.api.research.application;

import java.util.UUID;

public final class ResearchNotFoundException extends ResearchApplicationException {

    public ResearchNotFoundException(UUID researchId) {
        super(
                "RESEARCH_NOT_FOUND",
                "Research " + researchId + " does not exist or is not accessible",
                false,
                researchId
        );
    }
}
