package com.aiquantresearch.api.research.application;

import java.util.List;

public record ResearchPageView(List<ResearchItemView> items, PageMetadataView page) {

    public ResearchPageView {
        items = List.copyOf(items);
    }
}
