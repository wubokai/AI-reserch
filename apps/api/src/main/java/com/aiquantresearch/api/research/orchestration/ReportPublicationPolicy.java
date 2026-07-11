package com.aiquantresearch.api.research.orchestration;

import com.aiquantresearch.api.research.worker.StepExecutionException;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

final class ReportPublicationPolicy {

    private ReportPublicationPolicy() {
    }

    static void validate(
            DataMode mode,
            JsonNode report,
            List<StoredSource> sources,
            List<StoredEvidence> evidence
    ) {
        if (mode == DataMode.MIXED_TEST) {
            throw failure(
                    "REPORT_DATA_MODE_BLOCKED",
                    "MIXED_TEST research cannot publish a user-visible report"
            );
        }
        if (!mode.name().equals(report.path("dataMode").asText())) {
            throw failure(
                    "REPORT_DATA_MODE_MISMATCH",
                    "The validated report data mode does not match its research job"
            );
        }
        boolean expectedDemo = mode == DataMode.MOCK;
        if (sources.stream().anyMatch(source -> source.demoData() != expectedDemo)) {
            throw failure(
                    "REPORT_SOURCE_MODE_MISMATCH",
                    "A report source does not match its research data mode"
            );
        }
        if (evidence.isEmpty()) {
            throw failure(
                    "EVIDENCE_REGISTRY_EMPTY",
                    "A validated report cannot publish without registered Evidence"
            );
        }
        if (evidence.stream().anyMatch(item -> item.demoData() != expectedDemo)) {
            throw failure(
                    "REPORT_EVIDENCE_MODE_MISMATCH",
                    "Report Evidence does not match its research data mode"
            );
        }
    }

    private static StepExecutionException failure(String code, String message) {
        return new StepExecutionException(code, message, false);
    }
}
