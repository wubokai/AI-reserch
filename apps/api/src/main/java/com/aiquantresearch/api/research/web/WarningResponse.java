package com.aiquantresearch.api.research.web;

import java.util.List;

public record WarningResponse(String code, String message, List<String> evidenceIds) {
    static WarningResponse fromMessage(String message) {
        return new WarningResponse("RESEARCH_WARNING", message, List.of());
    }
}
