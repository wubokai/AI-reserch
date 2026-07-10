package com.aiquantresearch.api.research.provider;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FundamentalMetric(
        String name,
        BigDecimal value,
        String unit,
        String periodType,
        LocalDate periodEndDate
) {
}
