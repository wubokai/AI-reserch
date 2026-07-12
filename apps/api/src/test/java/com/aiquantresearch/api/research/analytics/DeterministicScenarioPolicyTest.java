package com.aiquantresearch.api.research.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DeterministicScenarioPolicyTest {

    @Test
    void anchorsProfitableCompaniesToCurrentEvEbitda() {
        var scenarios = DeterministicScenarioPolicy.create(
                new BigDecimal("1000"),
                new BigDecimal("200"),
                new BigDecimal("10"),
                new BigDecimal("100"),
                BigDecimal.ZERO
        );

        assertThat(scenarios).allSatisfy(scenario ->
                assertThat(scenario.valuationMethod()).isEqualTo("EV_EBITDA"));
        assertThat(scenarios.get(1).valuationMultiple()).isEqualByComparingTo("5");
    }

    @Test
    void usesRevenueMultipleForUnprofitableCompanies() {
        var scenarios = DeterministicScenarioPolicy.create(
                new BigDecimal("1000"),
                new BigDecimal("-200"),
                new BigDecimal("10"),
                new BigDecimal("100"),
                BigDecimal.ZERO
        );

        assertThat(scenarios).allSatisfy(scenario -> {
            assertThat(scenario.valuationMethod()).isEqualTo("EV_REVENUE");
            assertThat(scenario.valuationMultiple()).isPositive();
        });
        assertThat(scenarios.get(1).valuationMultiple()).isEqualByComparingTo("1");
    }
}
