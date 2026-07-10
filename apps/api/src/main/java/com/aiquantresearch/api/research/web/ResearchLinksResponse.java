package com.aiquantresearch.api.research.web;

import java.util.UUID;

public record ResearchLinksResponse(
        String self,
        String status,
        String evidence,
        String reports,
        String export
) {
    public static ResearchLinksResponse forResearch(UUID researchId) {
        String base = "/api/v1/research/" + researchId;
        return new ResearchLinksResponse(
                base,
                base + "/status",
                base + "/evidence",
                base + "/reports",
                base + "/export"
        );
    }
}
