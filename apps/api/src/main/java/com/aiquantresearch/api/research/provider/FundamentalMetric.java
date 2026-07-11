package com.aiquantresearch.api.research.provider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record FundamentalMetric(
        String name,
        BigDecimal value,
        String unit,
        String periodType,
        LocalDate periodEndDate,
        String taxonomy,
        String concept,
        String accessionNumber,
        LocalDate filedDate,
        boolean derived,
        List<String> componentConcepts
) {
    public FundamentalMetric {
        componentConcepts = componentConcepts == null ? List.of() : List.copyOf(componentConcepts);
    }

    public FundamentalMetric(
            String name,
            BigDecimal value,
            String unit,
            String periodType,
            LocalDate periodEndDate
    ) {
        this(name, value, unit, periodType, periodEndDate,
                null, null, null, null, false, List.of());
    }
}
