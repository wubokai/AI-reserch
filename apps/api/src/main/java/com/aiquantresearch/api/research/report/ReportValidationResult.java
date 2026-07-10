package com.aiquantresearch.api.research.report;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record ReportValidationResult(
        boolean valid,
        JsonNode report,
        List<String> warnings,
        boolean partial
) {
    public ReportValidationResult {
        report = report == null ? null : report.deepCopy();
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
