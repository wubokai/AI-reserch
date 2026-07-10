package com.aiquantresearch.api.research.provider;

import java.time.LocalDate;
import java.util.List;

public record FundamentalDataSnapshot(
        String fixtureVersion,
        String symbol,
        LocalDate asOfDate,
        List<FundamentalMetric> metrics,
        List<ScenarioAssumption> scenarios,
        String watermark
) {
    public FundamentalDataSnapshot {
        metrics = List.copyOf(metrics);
        scenarios = List.copyOf(scenarios);
    }
}
