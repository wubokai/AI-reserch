package com.aiquantresearch.api.research.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiquantresearch.api.research.application.CanonicalHashService;
import com.aiquantresearch.api.research.provider.mock.MockFixtureCatalog;
import com.aiquantresearch.api.research.provider.mock.MockFundamentalDataProvider;
import com.aiquantresearch.api.research.provider.mock.MockMarketDataProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
}
