package com.aiquantresearch.api.research.web;

import com.aiquantresearch.api.research.application.ResearchPageView;
import java.util.List;

public record ResearchPageResponse(
        List<ResearchItemResponse> items,
        PageMetadataResponse page
) {
    static ResearchPageResponse from(ResearchPageView view) {
        return new ResearchPageResponse(
                view.items().stream().map(ResearchItemResponse::from).toList(),
                PageMetadataResponse.from(view.page())
        );
    }
}
