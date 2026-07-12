package com.aiquantresearch.api.research.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketDataSnapshot(
        String provider,
        String schemaVersion,
        String fixtureVersion,
        String symbol,
        LocalDate periodStart,
        LocalDate periodEnd,
        Instant retrievedAt,
        String sourceUrl,
        String rawDataHash,
        List<PriceBar> prices,
        String attribution,
        String watermark,
        String freshnessStatus,
        boolean demoData,
        String licensePolicyVersion,
        String priceAdjustment
) {
    public MarketDataSnapshot {
        prices = List.copyOf(prices);
    }

    public MarketDataSnapshot(
            String fixtureVersion,
            String symbol,
            LocalDate periodStart,
            LocalDate periodEnd,
            List<PriceBar> prices,
            String watermark
    ) {
        this(
                "MOCK_MARKET_V1",
                "mock_market_daily_v1",
                fixtureVersion,
                symbol,
                periodStart,
                periodEnd,
                null,
                null,
                null,
                prices,
                null,
                watermark,
                "FRESH",
                true,
                null,
                "SYNTHETIC_UNADJUSTED_EQUIVALENT"
        );
    }

    public MarketDataSnapshot within(LocalDate requestedStart, LocalDate requestedEnd) {
        if (requestedStart == null || requestedEnd == null || requestedStart.isAfter(requestedEnd)) {
            throw new IllegalArgumentException("A valid market-data date range is required");
        }
        List<PriceBar> selected = prices.stream()
                .filter(bar -> !bar.date().isBefore(requestedStart))
                .filter(bar -> !bar.date().isAfter(requestedEnd))
                .toList();
        LocalDate selectedStart = selected.isEmpty() ? requestedStart : selected.getFirst().date();
        LocalDate selectedEnd = selected.isEmpty() ? requestedEnd : selected.getLast().date();
        return new MarketDataSnapshot(
                provider,
                schemaVersion,
                fixtureVersion,
                symbol,
                selectedStart,
                selectedEnd,
                retrievedAt,
                sourceUrl,
                rawDataHash,
                selected,
                attribution,
                watermark,
                freshnessStatus,
                demoData,
                licensePolicyVersion,
                priceAdjustment
        );
    }
}
