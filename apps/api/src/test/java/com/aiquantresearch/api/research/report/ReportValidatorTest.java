package com.aiquantresearch.api.research.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ReportValidatorTest {

    private final ReportTestFixture fixture = new ReportTestFixture();
    private final ReportValidator validator = new ReportValidator();

    @Test
    void rejectsEvidenceOutsideAllowlist() {
        ObjectNode report = fixture.report().deepCopy();
        ((ObjectNode) report.path("sections").get(0).path("claims").get(0))
                .withArray("evidenceIds")
                .set(0, fixture.objectMapper().getNodeFactory().textNode("ev_fabricated"));

        ReportValidationResult result = validator.validate(
                report, fixture.quantResults(), fixture.evidence(), fixture.context()
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.partial()).isTrue();
        assertThat(result.warnings()).anyMatch(value -> value.startsWith("EVIDENCE_ID_NOT_ALLOWED:"));
    }

    @Test
    void rejectsNumericValuesThatDoNotResolveToCalculation() {
        ObjectNode report = fixture.report().deepCopy();
        ObjectNode reference = (ObjectNode) report.path("sections").get(1)
                .path("claims").get(0).path("numericReferences").get(0);
        reference.put("normalizedValue", "999");

        ReportValidationResult result = validator.validate(
                report, fixture.quantResults(), fixture.evidence(), fixture.context()
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.warnings()).anyMatch(value -> value.startsWith("NUMERIC_VALUE_MISMATCH:"));
        assertThat(result.warnings()).anyMatch(value -> value.startsWith("NUMERIC_STORED_VALUE_MISMATCH:"));
    }

    @Test
    void rejectsContextDriftMissingWatermarkAndUnknownStructure() {
        ObjectNode report = fixture.report().deepCopy();
        report.put("dataMode", "REAL");
        report.put("disclaimer", "Nothing to see here.");
        report.put("unexpected", "field");
        report.put("asOfDate", "2024-01-02");
        report.put("title", "Undisclosed report");
        ((ObjectNode) report.path("dataQuality")).withArray("limitations").removeAll();
        report.path("sections").forEach(section ->
                ((ObjectNode) section).put("transitionText", "No disclosure."));

        ReportValidationResult result = validator.validate(
                report, fixture.quantResults(), fixture.evidence(), fixture.context()
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.warnings()).anyMatch(value -> value.startsWith("DATA_MODE_CONTEXT_MISMATCH:"));
        assertThat(result.warnings()).anyMatch(value -> value.startsWith("AS_OF_DATE_EVIDENCE_MISMATCH:"));
        assertThat(result.warnings()).anyMatch(value -> value.startsWith("DEMO_WATERMARK_MISSING:"));
        assertThat(result.warnings()).anyMatch(value -> value.startsWith("UNKNOWN_FIELD:"));
    }
}
