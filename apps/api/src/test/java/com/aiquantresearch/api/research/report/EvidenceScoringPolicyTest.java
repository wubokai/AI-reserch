package com.aiquantresearch.api.research.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class EvidenceScoringPolicyTest {

    @Test
    void evidenceQualityUsesVersionedSourceFreshnessAndTraceWeights() {
        assertThat(EvidenceScoringPolicy.evidenceQuality(
                true, false, "FRESH", true, true
        )).isEqualByComparingTo("1.0000");
        assertThat(EvidenceScoringPolicy.evidenceQuality(
                false, true, "STALE", true, false
        )).isEqualByComparingTo(new BigDecimal("0.5100"));
        assertThat(EvidenceScoringPolicy.evidenceQuality(
                false, false, "VERY_STALE", false, false
        )).isEqualByComparingTo(new BigDecimal("0.1500"));
    }

    @Test
    void claimConfidenceUsesTopThreeTypeFactorAndConflictCap() {
        ReportTestFixture fixture = new ReportTestFixture();
        var evidence = fixture.evidence().stream().limit(3).toList();

        assertThat(EvidenceScoringPolicy.claimConfidence("FACT", evidence, true))
                .isEqualByComparingTo("0.60");
        assertThat(EvidenceScoringPolicy.claimConfidence("INFERENCE", evidence, false))
                .isEqualByComparingTo("0.81");
    }
}
