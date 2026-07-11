package com.aiquantresearch.api.research.provider;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record FilingSnapshot(
        String provider,
        String schemaVersion,
        String fixtureVersion,
        String symbol,
        LocalDate asOfDate,
        Instant retrievedAt,
        String sourceUrl,
        String rawDataHash,
        List<FilingDocument> filings,
        String watermark,
        boolean demoData,
        String freshnessStatus,
        String licensePolicyVersion
) {
    public FilingSnapshot {
        filings = List.copyOf(filings);
    }

    public FilingSnapshot limitedTo(int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum filings must be positive");
        }
        List<FilingDocument> selected = filings.stream().limit(maximum).toList();
        return new FilingSnapshot(
                provider,
                schemaVersion,
                fixtureVersion,
                symbol,
                asOfDate,
                retrievedAt,
                sourceUrl,
                rawDataHash,
                selected,
                watermark,
                demoData,
                freshnessStatus,
                licensePolicyVersion
        );
    }
}
