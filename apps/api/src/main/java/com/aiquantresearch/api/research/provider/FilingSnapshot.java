package com.aiquantresearch.api.research.provider;

import java.time.LocalDate;
import java.util.List;

public record FilingSnapshot(
        String fixtureVersion,
        String symbol,
        LocalDate asOfDate,
        List<FilingDocument> filings,
        String watermark
) {
    public FilingSnapshot {
        filings = List.copyOf(filings);
    }
}
