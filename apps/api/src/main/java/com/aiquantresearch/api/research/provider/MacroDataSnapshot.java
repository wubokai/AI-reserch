package com.aiquantresearch.api.research.provider;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record MacroDataSnapshot(
        String provider,
        String schemaVersion,
        String fixtureVersion,
        LocalDate asOfDate,
        LocalDate vintageDate,
        Instant retrievedAt,
        String sourceUrl,
        String rawDataHash,
        List<MacroSeries> series,
        String attribution,
        String watermark,
        boolean demoData,
        String freshnessStatus,
        String licensePolicyVersion
) {
    public MacroDataSnapshot {
        series = List.copyOf(series);
    }

    public MacroDataSnapshot(
            String fixtureVersion,
            LocalDate asOfDate,
            List<MacroSeries> series,
            String watermark
    ) {
        this(
                null,
                null,
                fixtureVersion,
                asOfDate,
                null,
                null,
                null,
                null,
                series,
                null,
                watermark,
                true,
                "FRESH",
                null
        );
    }
}
