package com.aiquantresearch.api.research.provider.mock;

import com.aiquantresearch.api.research.provider.MarketDataProvider;
import com.aiquantresearch.api.research.provider.MarketDataSnapshot;
import com.aiquantresearch.api.research.provider.PriceBar;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.providers.market", havingValue = "mock", matchIfMissing = true)
public class MockMarketDataProvider implements MarketDataProvider {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private final MockFixtureCatalog catalog;

    public MockMarketDataProvider(MockFixtureCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public MarketDataSnapshot fetchFiveYearDaily(String symbol) {
        var manifest = catalog.manifest();
        var fixture = catalog.security(symbol);
        List<PriceBar> prices = generatePrices(
                fixture,
                manifest.periodStart(),
                manifest.asOfDate()
        );
        return new MarketDataSnapshot(
                manifest.fixtureVersion(),
                fixture.symbol(),
                manifest.periodStart(),
                manifest.asOfDate(),
                prices,
                manifest.watermark()
        );
    }

    private static List<PriceBar> generatePrices(
            MockFixtureCatalog.SecurityFixture fixture,
            LocalDate start,
            LocalDate end
    ) {
        List<PriceBar> result = new ArrayList<>(1_310);
        int tradingIndex = 0;
        int seed = fixture.symbol().chars().sum();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY
                    || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            long primaryCycle = Math.floorMod(tradingIndex * 37 + seed, 97) - 48L;
            long secondaryCycle = Math.floorMod(tradingIndex * 11 + seed, 41) - 20L;
            long closeCents = fixture.basePriceCents()
                    + fixture.dailyTrendCents() * tradingIndex
                    + primaryCycle * fixture.cycleAmplitudeCents() / 48L
                    + secondaryCycle * fixture.cycleAmplitudeCents() / 125L;
            closeCents = Math.max(100L, closeCents);
            long openOffset = Math.floorMod(tradingIndex * 13 + seed, 31) - 15L;
            long openCents = Math.max(100L, closeCents + openOffset * Math.max(1L, closeCents / 3_000L));
            long wick = Math.max(2L, closeCents / 160L);
            long highCents = Math.max(openCents, closeCents) + wick;
            long lowCents = Math.max(1L, Math.min(openCents, closeCents) - wick);
            long volume = fixture.volumeBase()
                    + Math.floorMod((long) tradingIndex * 7919L + seed * 997L, 3_500_000L);
            result.add(new PriceBar(
                    date,
                    dollars(openCents),
                    dollars(highCents),
                    dollars(lowCents),
                    dollars(closeCents),
                    dollars(closeCents),
                    volume
            ));
            tradingIndex++;
        }
        return List.copyOf(result);
    }

    private static BigDecimal dollars(long cents) {
        return BigDecimal.valueOf(cents)
                .divide(ONE_HUNDRED, 2, RoundingMode.UNNECESSARY);
    }
}
