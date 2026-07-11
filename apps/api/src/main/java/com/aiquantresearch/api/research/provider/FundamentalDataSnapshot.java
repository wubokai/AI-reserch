package com.aiquantresearch.api.research.provider;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record FundamentalDataSnapshot(
        String provider,
        String schemaVersion,
        String fixtureVersion,
        String symbol,
        LocalDate asOfDate,
        Instant retrievedAt,
        String sourceUrl,
        String rawDataHash,
        List<FundamentalMetric> metrics,
        List<ScenarioAssumption> scenarios,
        List<String> warnings,
        String watermark,
        boolean demoData,
        String freshnessStatus,
        String licensePolicyVersion
) {
    public FundamentalDataSnapshot {
        metrics = List.copyOf(metrics);
        scenarios = List.copyOf(scenarios);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public FundamentalDataSnapshot(
            String fixtureVersion,
            String symbol,
            LocalDate asOfDate,
            List<FundamentalMetric> metrics,
            List<ScenarioAssumption> scenarios,
            String watermark
    ) {
        this(null, null, fixtureVersion, symbol, asOfDate, null, null, null,
                metrics, scenarios, List.of(), watermark, true, "FRESH", null);
    }
}
