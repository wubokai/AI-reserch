package com.aiquantresearch.api.research.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ReportRepairServiceTest {

    private final ReportTestFixture fixture = new ReportTestFixture();
    private final ReportValidator validator = new ReportValidator();
    private final ReportRepairService repairService = new ReportRepairService();

    @Test
    void prunesUnsafeNumericClaimWithoutAddingEvidence() {
        ObjectNode candidate = fixture.report().deepCopy();
        ObjectNode unsafeClaim = (ObjectNode) candidate.path("bullCase").get(0);
        String unsafeId = unsafeClaim.path("id").asText();
        ((ObjectNode) unsafeClaim.path("numericReferences").get(0))
                .put("normalizedValue", "999999");
        var beforeEvidence = fixture.evidence().stream()
                .map(item -> item.publicId())
                .sorted()
                .toList();
        ReportValidationResult initial = validator.validate(
                candidate, fixture.quantResults(), fixture.evidence(), fixture.context()
        );

        var repaired = repairService.repairOnce(
                candidate,
                initial.warnings(),
                fixture.evidence(),
                fixture.quantResults(),
                fixture.context()
        );
        ReportValidationResult finalValidation = validator.validate(
                repaired.report(), fixture.quantResults(), fixture.evidence(), fixture.context()
        );

        assertThat(initial.valid()).isFalse();
        assertThat(repaired.prunedClaimIds()).containsExactly(unsafeId);
        assertThat(repaired.report().path("bullCase").findValuesAsText("id"))
                .doesNotContain(unsafeId);
        assertThat(finalValidation.valid()).as(finalValidation.warnings().toString()).isTrue();
        assertThat(fixture.evidence().stream().map(item -> item.publicId()).sorted().toList())
                .isEqualTo(beforeEvidence);
    }

    @Test
    void repairsDeterministicConfidenceWithoutPruningClaim() {
        ObjectNode candidate = fixture.report().deepCopy();
        ObjectNode claim = (ObjectNode) candidate.path("bullCase").get(0);
        String claimId = claim.path("id").asText();
        claim.put("confidence", 0.01);
        ReportValidationResult initial = validator.validate(
                candidate, fixture.quantResults(), fixture.evidence(), fixture.context()
        );

        var repaired = repairService.repairOnce(
                candidate,
                initial.warnings(),
                fixture.evidence(),
                fixture.quantResults(),
                fixture.context()
        );
        ReportValidationResult finalValidation = validator.validate(
                repaired.report(), fixture.quantResults(), fixture.evidence(), fixture.context()
        );

        assertThat(repaired.prunedClaimIds()).isEmpty();
        assertThat(repaired.report().toString()).contains(claimId);
        assertThat(finalValidation.valid()).as(finalValidation.warnings().toString()).isTrue();
    }
}
