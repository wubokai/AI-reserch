package com.aiquantresearch.api.research.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReportDepthTest {

    @Test
    void depthPoliciesIncreaseRetrievalAndModelBudgetsMonotonically() {
        assertThat(ReportDepth.QUICK.maxFilings())
                .isLessThan(ReportDepth.STANDARD.maxFilings());
        assertThat(ReportDepth.STANDARD.maxFilings())
                .isLessThan(ReportDepth.DEEP.maxFilings());
        assertThat(ReportDepth.QUICK.maxEvidenceItems())
                .isLessThan(ReportDepth.STANDARD.maxEvidenceItems());
        assertThat(ReportDepth.STANDARD.maxEvidenceItems())
                .isLessThan(ReportDepth.DEEP.maxEvidenceItems());
        assertThat(ReportDepth.QUICK.maxCalculations())
                .isLessThan(ReportDepth.STANDARD.maxCalculations());
        assertThat(ReportDepth.STANDARD.maxCalculations())
                .isLessThan(ReportDepth.DEEP.maxCalculations());
        assertThat(ReportDepth.QUICK.maxToolRounds(3)).isZero();
        assertThat(ReportDepth.STANDARD.maxToolRounds(3)).isOne();
        assertThat(ReportDepth.DEEP.maxToolRounds(3)).isEqualTo(3);
    }
}
