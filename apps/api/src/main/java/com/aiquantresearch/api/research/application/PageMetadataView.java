package com.aiquantresearch.api.research.application;

public record PageMetadataView(
        int number,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
}
