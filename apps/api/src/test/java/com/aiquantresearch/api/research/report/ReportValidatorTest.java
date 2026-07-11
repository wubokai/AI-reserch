package com.aiquantresearch.api.research.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiquantresearch.api.research.orchestration.StoredEvidence;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
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
        ObjectNode reference = (ObjectNode) report.path("sections").get(0)
                .path("claims").get(1).path("numericReferences").get(0);
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

    @Test
    void rejectsDateReferenceThatDoesNotMatchEvidence() {
        ObjectNode report = fixture.report().deepCopy();
        ObjectNode reference = (ObjectNode) report.path("sections").get(0)
                .path("claims").get(0).path("dateReferences").get(0);
        reference.put("normalizedDate", "2023-12-28");

        ReportValidationResult result = validator.validate(
                report, fixture.quantResults(), fixture.evidence(), fixture.context()
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.warnings()).anyMatch(value -> value.startsWith("DATE_VALUE_INVALID:")
                || value.startsWith("DATE_VALUE_MISMATCH:"));
    }

    @Test
    void rejectsStaleEvidenceWhenDataQualityDoesNotDiscloseIt() {
        List<StoredEvidence> evidence = new ArrayList<>(fixture.evidence());
        StoredEvidence original = evidence.getFirst();
        evidence.set(0, new StoredEvidence(
                original.id(), original.publicId(), original.evidenceType(), original.title(),
                original.summary(), original.value(), original.unit(), original.sourceSnapshotId(),
                original.quantResultId(), original.qualityScore(), original.primarySource(),
                "STALE", original.effectiveDate(), original.demoData()
        ));

        ReportValidationResult result = validator.validate(
                fixture.report(), fixture.quantResults(), evidence, fixture.context()
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.warnings())
                .anyMatch(value -> value.startsWith("STALE_EVIDENCE_DISCLOSURE_MISMATCH:"));
    }

    @Test
    void rejectsFactSupportedOnlyByInferenceEvidence() {
        List<StoredEvidence> evidence = fixture.evidence().stream().map(item -> {
            if (!"ev_market_snapshot".equals(item.publicId())) {
                return item;
            }
            ObjectNode value = item.value().deepCopy();
            value.put("supportKind", "INFERENCE");
            return new StoredEvidence(
                    item.id(), item.publicId(), item.evidenceType(), item.title(), item.summary(),
                    value, item.unit(), item.sourceSnapshotId(), item.quantResultId(),
                    item.qualityScore(), item.primarySource(), item.freshnessStatus(),
                    item.effectiveDate(), item.demoData()
            );
        }).toList();

        ReportValidationResult result = validator.validate(
                fixture.report(), fixture.quantResults(), evidence, fixture.context()
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.warnings())
                .anyMatch(value -> value.startsWith("FACT_SUPPORTED_ONLY_BY_INFERENCE:"));
    }

    @Test
    void permitsOneEvidenceItemToSupportDifferentClaimTypes() {
        ObjectNode report = fixture.report().deepCopy();
        ObjectNode inference = (ObjectNode) report.path("catalysts").get(0);
        inference.put("claimType", "INFERENCE");
        inference.put("confidence", 0.81);
        inference.withArray("limitations").add("This is an interpretive scenario statement.");
        inference.withArray("evidenceIds").removeAll().add("ev_market_snapshot");

        ReportValidationResult result = validator.validate(
                report, fixture.quantResults(), fixture.evidence(), fixture.context()
        );

        assertThat(result.valid()).as(result.warnings().toString()).isTrue();
    }
}
