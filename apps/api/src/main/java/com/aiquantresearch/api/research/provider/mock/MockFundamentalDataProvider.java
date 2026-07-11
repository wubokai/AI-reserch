package com.aiquantresearch.api.research.provider.mock;

import com.aiquantresearch.api.research.provider.FundamentalDataProvider;
import com.aiquantresearch.api.research.provider.FundamentalDataSnapshot;
import com.aiquantresearch.api.research.provider.FundamentalMetric;
import com.aiquantresearch.api.research.provider.ScenarioAssumption;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.providers.fundamental",
        havingValue = "mock",
        matchIfMissing = true
)
public class MockFundamentalDataProvider implements FundamentalDataProvider {

    private static final List<String> METRIC_ORDER = List.of(
            "revenue",
            "ebitda",
            "operatingIncome",
            "netIncome",
            "netDebt",
            "dilutedShares",
            "grossMargin",
            "freeCashFlow"
    );

    private final MockFixtureCatalog catalog;

    public MockFundamentalDataProvider(MockFixtureCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public FundamentalDataSnapshot fetch(String symbol) {
        var manifest = catalog.manifest();
        var fixture = catalog.security(symbol);
        List<FundamentalMetric> metrics = new ArrayList<>();
        for (String name : METRIC_ORDER) {
            BigDecimal value = fixture.fundamentals().get(name);
            if (value == null) {
                continue;
            }
            metrics.add(new FundamentalMetric(
                    name,
                    value,
                    unit(name),
                    periodType(name),
                    manifest.asOfDate()
            ));
        }
        List<ScenarioAssumption> scenarios = fixture.scenarios().stream()
                .map(item -> new ScenarioAssumption(
                        item.name(),
                        item.revenueGrowth(),
                        item.targetEbitdaMargin(),
                        item.evToEbitdaMultiple(),
                        item.probability()
                ))
                .toList();
        return new FundamentalDataSnapshot(
                manifest.fixtureVersion(),
                fixture.symbol(),
                manifest.asOfDate(),
                metrics,
                scenarios,
                manifest.watermark()
        );
    }

    private static String unit(String name) {
        return switch (name) {
            case "dilutedShares" -> "SHARES";
            case "grossMargin" -> "RATIO";
            default -> "USD";
        };
    }

    private static String periodType(String name) {
        return switch (name) {
            case "netDebt", "dilutedShares" -> "POINT_IN_TIME";
            default -> "TTM";
        };
    }
}
