package com.aiquantresearch.api.research.provider.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiquantresearch.api.research.provider.ProviderDataNotFoundException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MockProvidersTest {

    private MockFixtureCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new MockFixtureCatalog(JsonMapper.builder().findAndAddModules().build());
    }

    @Test
    void generatesStableFiveYearAdjustedOhlcvForEverySupportedSymbol() {
        var provider = new MockMarketDataProvider(catalog);

        for (String symbol : new String[]{"MU", "NVDA", "RKLB", "SPY", "QQQ"}) {
            var first = provider.fetchFiveYearDaily(symbol);
            var second = provider.fetchFiveYearDaily(symbol);

            assertThat(first).isEqualTo(second);
            assertThat(first.fixtureVersion()).isEqualTo("mock_fixture_v1");
            assertThat(first.periodStart()).hasToString("2019-01-02");
            assertThat(first.periodEnd()).hasToString("2023-12-29");
            assertThat(first.watermark()).isEqualTo(MockFixtureCatalog.EXPECTED_WATERMARK);
            assertThat(first.prices()).hasSizeGreaterThan(1_290);
            assertThat(first.prices()).allSatisfy(bar -> {
                assertThat(bar.date().getDayOfWeek())
                        .isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
                assertThat(bar.adjustedClose()).isEqualByComparingTo(bar.close());
                assertThat(bar.high()).isGreaterThanOrEqualTo(bar.open());
                assertThat(bar.high()).isGreaterThanOrEqualTo(bar.close());
                assertThat(bar.low()).isLessThanOrEqualTo(bar.open());
                assertThat(bar.low()).isLessThanOrEqualTo(bar.close());
                assertThat(bar.volume()).isPositive();
            });
        }
    }

    @Test
    void exposesDeterministicFundamentalScenarioFilingAndMacroFixtures() {
        var fundamentals = new MockFundamentalDataProvider(catalog).fetch("MU");
        var filings = new MockFilingProvider(catalog).fetch("MU");
        var macro = new MockMacroDataProvider(catalog).fetch();

        assertThat(fundamentals.metrics()).extracting("name")
                .containsExactly(
                        "revenue",
                        "ebitda",
                        "operatingIncome",
                        "netIncome",
                        "netDebt",
                        "dilutedShares",
                        "grossMargin",
                        "freeCashFlow"
                );
        assertThat(fundamentals.scenarios()).extracting("name")
                .containsExactly("BULL", "BASE", "BEAR");
        assertThat(fundamentals.scenarios()).extracting("probability")
                .containsExactly(
                        new BigDecimal("0.25"),
                        new BigDecimal("0.50"),
                        new BigDecimal("0.25")
                );
        assertThat(filings.filings()).hasSize(2);
        assertThat(macro.series()).hasSize(2);
        assertThat(macro.watermark()).isEqualTo(MockFixtureCatalog.EXPECTED_WATERMARK);
    }

    @Test
    void rejectsUnsupportedSymbolsWithoutNetworkFallback() {
        var provider = new MockMarketDataProvider(catalog);

        assertThatThrownBy(() -> provider.fetchFiveYearDaily("AAPL"))
                .isInstanceOf(ProviderDataNotFoundException.class)
                .hasMessageContaining("No deterministic Mock fixture");
    }
}
