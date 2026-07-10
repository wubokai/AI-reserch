package com.aiquantresearch.api.research.provider;

import java.math.BigDecimal;

public record ScenarioAssumption(
        String name,
        BigDecimal revenueGrowth,
        BigDecimal targetEbitdaMargin,
        BigDecimal evToEbitdaMultiple,
        BigDecimal probability
) {
}
