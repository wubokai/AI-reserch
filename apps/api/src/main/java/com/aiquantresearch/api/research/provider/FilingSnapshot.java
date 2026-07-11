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
}
