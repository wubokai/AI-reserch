package com.aiquantresearch.api.research.provider;

import java.time.LocalDate;
import java.util.List;

public record MarketDataSnapshot(
        String fixtureVersion,
        String symbol,
        LocalDate periodStart,
        LocalDate periodEnd,
        List<PriceBar> prices,
        String watermark
) {
    public MarketDataSnapshot {
        prices = List.copyOf(prices);
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
                fixtureVersion,
                symbol,
                selectedStart,
                selectedEnd,
                selected,
                watermark
        );
    }
}
