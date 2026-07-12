package com.aiquantresearch.api.research.provider;

import java.math.BigDecimal;

public record ScenarioAssumption(
        String name,
        BigDecimal revenueGrowth,
        BigDecimal targetEbitdaMargin,
        BigDecimal evToEbitdaMultiple,
        BigDecimal probability,
        String valuationMethod,
        BigDecimal valuationMultiple
) {

    public ScenarioAssumption(
            String name,
            BigDecimal revenueGrowth,
            BigDecimal targetEbitdaMargin,
            BigDecimal evToEbitdaMultiple,
            BigDecimal probability
    ) {
        this(
                name,
                revenueGrowth,
                targetEbitdaMargin,
                evToEbitdaMultiple,
                probability,
                "EV_EBITDA",
                evToEbitdaMultiple
        );
    }
}
