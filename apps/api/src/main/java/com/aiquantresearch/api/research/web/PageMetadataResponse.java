package com.aiquantresearch.api.research.web;

import com.aiquantresearch.api.research.application.PageMetadataView;

public record PageMetadataResponse(
        int number,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    static PageMetadataResponse from(PageMetadataView view) {
        return new PageMetadataResponse(
                view.number(),
                view.size(),
                view.totalElements(),
                view.totalPages(),
                view.first(),
                view.last()
        );
    }
}
