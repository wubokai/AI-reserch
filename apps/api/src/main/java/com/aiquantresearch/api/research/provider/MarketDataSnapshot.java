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
}
