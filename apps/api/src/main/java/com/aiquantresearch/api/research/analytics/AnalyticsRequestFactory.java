package com.aiquantresearch.api.research.analytics;

import com.aiquantresearch.api.research.application.CanonicalHashService;
import com.aiquantresearch.api.research.provider.FundamentalDataSnapshot;
import com.aiquantresearch.api.research.provider.FundamentalMetric;
import com.aiquantresearch.api.research.provider.MarketDataSnapshot;
import com.aiquantresearch.api.research.provider.PriceBar;
import com.aiquantresearch.api.research.provider.ScenarioAssumption;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class AnalyticsRequestFactory {

    private final ObjectMapper objectMapper;
    private final CanonicalHashService hashService;

    public AnalyticsRequestFactory(ObjectMapper objectMapper, CanonicalHashService hashService) {
        this.objectMapper = objectMapper;
        this.hashService = hashService;
    }

    public ObjectNode create(
            String securityType,
            MarketDataSnapshot target,
            String targetSnapshotId,
            MarketDataSnapshot benchmark,
            String benchmarkSnapshotId,
            FundamentalDataSnapshot fundamentals,
            String fundamentalSnapshotId
    ) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("schemaVersion", "analytics_full_request_v1");
        request.put("calculationVersion", "quant_v1");
        request.put("inputHash", "0".repeat(64));
        request.put("symbol", target.symbol());
        request.put("securityType", securityType);
        request.put("periodStart", target.periodStart().toString());
        request.put("periodEnd", target.periodEnd().toString());
        request.set("prices", priceBars(target.prices(), targetSnapshotId));
        request.put("benchmarkSymbol", benchmark.symbol());
        request.set("benchmarkPrices", priceBars(benchmark.prices(), benchmarkSnapshotId));
        request.put("riskFreeRateAnnual", "0.0200");
        request.put("minimumAcceptedReturnAnnual", "0.0600");
        request.set("fundamentals", fundamentals(fundamentals, fundamentalSnapshotId));
        request.set(
                "scenarioInput",
                scenarioInput(
                        target,
                        targetSnapshotId,
                        fundamentals,
                        fundamentalSnapshotId
                )
        );

        ObjectNode hashable = request.deepCopy();
        hashable.remove("inputHash");
        request.put("inputHash", hashService.hash(hashable));
        return request;
    }

    private ArrayNode priceBars(Iterable<PriceBar> bars, String snapshotId) {
        ArrayNode result = objectMapper.createArrayNode();
        for (PriceBar bar : bars) {
            ObjectNode item = result.addObject();
            item.put("date", bar.date().toString());
            item.put("open", decimal(bar.open()));
            item.put("high", decimal(bar.high()));
            item.put("low", decimal(bar.low()));
            item.put("close", decimal(bar.close()));
            item.put("adjustedClose", decimal(bar.adjustedClose()));
            item.put("volume", Long.toString(bar.volume()));
            item.put("sourceSnapshotId", snapshotId);
        }
        return result;
    }

    private ArrayNode fundamentals(
            FundamentalDataSnapshot snapshot,
            String snapshotId
    ) {
        ArrayNode result = objectMapper.createArrayNode();
        for (FundamentalMetric metric : snapshot.metrics()) {
            ObjectNode item = result.addObject();
            item.put("name", metric.name());
            item.put("value", decimal(metric.value()));
            item.put("unit", metric.unit());
            item.put("periodType", analyticsPeriodType(metric.periodType()));
            item.put("periodEndDate", metric.periodEndDate().toString());
            item.put("sourceSnapshotId", snapshotId);
        }
        return result;
    }

    private JsonNode scenarioInput(
            MarketDataSnapshot market,
            String marketSnapshotId,
            FundamentalDataSnapshot snapshot,
            String snapshotId
    ) {
        Map<String, FundamentalMetric> byName = snapshot.metrics().stream().collect(
                Collectors.toUnmodifiableMap(FundamentalMetric::name, Function.identity())
        );
        if (!byName.keySet().containsAll(
                        java.util.Set.of("revenue", "netDebt", "dilutedShares")
                )) {
            return objectMapper.nullNode();
        }
        List<ScenarioAssumption> assumptions = snapshot.scenarios();
        if (assumptions.isEmpty()) {
            FundamentalMetric profit = byName.get("ebitda");
            if (profit == null) {
                profit = byName.get("operatingIncome");
            }
            assumptions = DeterministicScenarioPolicy.create(
                    byName.get("revenue").value(),
                    profit == null ? null : profit.value(),
                    market.prices().getLast().adjustedClose(),
                    byName.get("dilutedShares").value(),
                    byName.get("netDebt").value()
            );
        }
        if (assumptions.size() != 3) {
            return objectMapper.nullNode();
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("baseRevenue", decimal(byName.get("revenue").value()));
        result.put("currentPrice", decimal(market.prices().getLast().adjustedClose()));
        result.put("netDebt", decimal(byName.get("netDebt").value()));
        result.put("dilutedShares", decimal(byName.get("dilutedShares").value()));
        result.put("currency", "USD");
        result.putArray("sourceSnapshotIds").add(marketSnapshotId).add(snapshotId);
        ArrayNode scenarios = result.putArray("scenarios");
        for (ScenarioAssumption scenario : assumptions) {
            ObjectNode item = scenarios.addObject();
            item.put("name", scenario.name());
            item.put("revenueGrowth", decimal(scenario.revenueGrowth()));
            item.put("targetEbitdaMargin", decimal(scenario.targetEbitdaMargin()));
            item.put("evToEbitdaMultiple", decimal(scenario.evToEbitdaMultiple()));
            item.put("valuationMethod", scenario.valuationMethod());
            item.put("valuationMultiple", decimal(scenario.valuationMultiple()));
            item.put("probability", decimal(scenario.probability()));
        }
        return result;
    }

    private static String analyticsPeriodType(String periodType) {
        return switch (periodType) {
            case "FY", "ANNUAL" -> "ANNUAL";
            case "Q1", "Q2", "Q3", "Q4", "QUARTER" -> "QUARTER";
            case "TTM" -> "TTM";
            case "POINT_IN_TIME" -> "POINT_IN_TIME";
            default -> throw new IllegalArgumentException(
                    "Unsupported fundamental period type: " + periodType
            );
        };
    }

    private static String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
