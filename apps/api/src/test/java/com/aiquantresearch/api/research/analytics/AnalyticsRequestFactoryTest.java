package com.aiquantresearch.api.research.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiquantresearch.api.research.application.CanonicalHashService;
import com.aiquantresearch.api.research.provider.FundamentalDataSnapshot;
import com.aiquantresearch.api.research.provider.FundamentalMetric;
import com.aiquantresearch.api.research.provider.mock.MockFixtureCatalog;
import com.aiquantresearch.api.research.provider.mock.MockFundamentalDataProvider;
import com.aiquantresearch.api.research.provider.mock.MockMarketDataProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalyticsRequestFactoryTest {

    @Test
    void buildsStableQuantV1RequestWithCompleteScenarioLineage() {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var hashService = new CanonicalHashService(mapper);
        var catalog = new MockFixtureCatalog(mapper);
        var market = new MockMarketDataProvider(catalog);
        var fundamentals = new MockFundamentalDataProvider(catalog);
        var factory = new AnalyticsRequestFactory(mapper, hashService);

        var first = factory.create(
                "COMMON_STOCK",
                market.fetchFiveYearDaily("MU"),
                "market-snapshot",
                market.fetchFiveYearDaily("SPY"),
                "benchmark-snapshot",
                fundamentals.fetch("MU"),
                "fundamental-snapshot"
        );
        var second = factory.create(
                "COMMON_STOCK",
                market.fetchFiveYearDaily("MU"),
                "market-snapshot",
                market.fetchFiveYearDaily("SPY"),
                "benchmark-snapshot",
                fundamentals.fetch("MU"),
                "fundamental-snapshot"
        );

        assertThat(first).isEqualTo(second);
        assertThat(first.path("calculationVersion").asText()).isEqualTo("quant_v1");
        assertThat(first.path("inputHash").asText()).matches("^[0-9a-f]{64}$");
        assertThat(first.path("prices")).hasSizeGreaterThan(1_290);
        assertThat(first.path("scenarioInput").path("sourceSnapshotIds"))
                .extracting(node -> node.asText())
                .containsExactly("market-snapshot", "fundamental-snapshot");
    }

    @Test
    void normalizesSecAnnualPeriodsAndBuildsTransparentRealDataScenarios() {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var catalog = new MockFixtureCatalog(mapper);
        var market = new MockMarketDataProvider(catalog);
        var factory = new AnalyticsRequestFactory(mapper, new CanonicalHashService(mapper));
        LocalDate periodEnd = LocalDate.parse("2025-12-31");
        var fundamentals = new FundamentalDataSnapshot(
                "SEC_EDGAR_XBRL",
                "sec_companyfacts_normalized_v1",
                null,
                "MU",
                periodEnd,
                Instant.parse("2026-01-02T00:00:00Z"),
                "https://data.sec.gov/api/xbrl/companyfacts/CIK0000000000.json",
                "a".repeat(64),
                List.of(
                        metric("revenue", "1000", "USD", "FY", periodEnd),
                        metric("ebitda", "300", "USD", "FY", periodEnd),
                        metric("netDebt", "100", "USD", "POINT_IN_TIME", periodEnd),
                        metric("dilutedShares", "10", "SHARES", "FY", periodEnd)
                ),
                List.of(),
                List.of(),
                null,
                false,
                "FRESH",
                "sec_public_edgar_2025_04_08"
        );

        var request = factory.create(
                "COMMON_STOCK",
                market.fetchFiveYearDaily("MU"),
                "market-snapshot",
                market.fetchFiveYearDaily("SPY"),
                "benchmark-snapshot",
                fundamentals,
                "fundamental-snapshot"
        );

        assertThat(request.path("fundamentals"))
                .extracting(node -> node.path("periodType").asText())
                .containsExactly("ANNUAL", "ANNUAL", "POINT_IN_TIME", "ANNUAL");
        assertThat(request.path("scenarioInput").path("scenarios"))
                .extracting(node -> node.path("name").asText())
                .containsExactly("BULL", "BASE", "BEAR");
        assertThat(request.path("scenarioInput").path("scenarios").get(1)
                .path("targetEbitdaMargin").asText()).isEqualTo("0.3");
    }

    private static FundamentalMetric metric(
            String name,
            String value,
            String unit,
            String periodType,
            LocalDate periodEnd
    ) {
        return new FundamentalMetric(
                name, new BigDecimal(value), unit, periodType, periodEnd
        );
    }
}
