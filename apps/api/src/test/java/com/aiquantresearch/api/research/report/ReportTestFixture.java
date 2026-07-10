package com.aiquantresearch.api.research.report;

import com.aiquantresearch.api.research.orchestration.ResearchExecutionContext;
import com.aiquantresearch.api.research.orchestration.StoredEvidence;
import com.aiquantresearch.api.research.orchestration.StoredQuantResult;
import com.aiquantresearch.api.research.orchestration.StoredSource;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ReportTestFixture {

    static final UUID RESEARCH_ID = UUID.fromString("8d2a444a-ae5d-4a4a-9b9f-b08100c0ffee");
    static final String AS_OF_DATE = "2023-12-29";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StoredSource market;
    private final StoredSource fundamentals;
    private final StoredSource filing;
    private final List<StoredQuantResult> quantResults;
    private final List<StoredEvidence> evidence;

    ReportTestFixture() {
        market = new StoredSource(
                uuid("source-market"),
                "MARKET_DATA",
                "NVDA",
                marketPayload(),
                "hash-market"
        );
        fundamentals = new StoredSource(
                uuid("source-fundamentals"),
                "FUNDAMENTALS",
                "NVDA",
                fundamentalPayload(),
                "hash-fundamentals"
        );
        filing = new StoredSource(
                uuid("source-filing"),
                "FILING",
                "NVDA",
                filingPayload(),
                "hash-filing"
        );
        quantResults = List.of(
                quant("total_return", "0.4", "RATIO"),
                quant("cagr", "0.08", "RATIO"),
                quant("annualized_volatility", "0.25", "RATIO"),
                quant("max_drawdown", "-0.35", "RATIO"),
                quant("sharpe_ratio", "1.1", "RATIO"),
                quant("scenario_bull_raw_implied_price", "150", "USD_PER_SHARE"),
                quant("scenario_bull_implied_price", "150", "USD_PER_SHARE"),
                quant("scenario_bull_upside_downside", "0.5", "RATIO"),
                quant("scenario_base_raw_implied_price", "100", "USD_PER_SHARE"),
                quant("scenario_base_implied_price", "100", "USD_PER_SHARE"),
                quant("scenario_base_upside_downside", "0", "RATIO"),
                quant("scenario_bear_raw_implied_price", "-10", "USD_PER_SHARE"),
                quant("scenario_bear_implied_price", "0", "USD_PER_SHARE"),
                quant("scenario_bear_upside_downside", "-1", "RATIO"),
                quant("weighted_scenario_value", "87.5", "USD_PER_SHARE")
        );
        List<StoredEvidence> items = new ArrayList<>();
        for (StoredQuantResult quant : quantResults) {
            items.add(new StoredEvidence(
                    uuid("evidence-" + quant.metricName()),
                    "ev_" + quant.metricName(),
                    "QUANT_RESULT",
                    quant.metricName(),
                    "Deterministic test calculation.",
                    quant.result(),
                    quant.unit(),
                    null,
                    quant.id()
            ));
        }
        ObjectNode marketValue = objectMapper.createObjectNode();
        marketValue.put("asOfDate", AS_OF_DATE);
        marketValue.put("latestAdjustedClose", "100");
        items.add(sourceEvidence(market, "ev_market_snapshot", "Synthetic market snapshot", marketValue));
        ObjectNode fundamentalValue = objectMapper.createObjectNode();
        fundamentalValue.put("asOfDate", AS_OF_DATE);
        fundamentalValue.set("metrics", fundamentals.payload().path("metrics"));
        items.add(sourceEvidence(
                fundamentals,
                "ev_fundamental_snapshot",
                "Synthetic fundamentals",
                fundamentalValue
        ));
        ObjectNode filingValue = objectMapper.createObjectNode();
        filingValue.put("asOfDate", AS_OF_DATE);
        filingValue.set("filings", filing.payload().path("filings"));
        items.add(sourceEvidence(filing, "ev_filing_snapshot", "Synthetic filings", filingValue));
        evidence = List.copyOf(items);
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }

    ResearchExecutionContext context() {
        return context("zh-CN");
    }

    ResearchExecutionContext context(String locale) {
        return context(locale, true);
    }

    ResearchExecutionContext context(String locale, boolean includeFundamentalAnalysis) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("includeFundamentalAnalysis", includeFundamentalAnalysis);
        return new ResearchExecutionContext(
                RESEARCH_ID,
                uuid("owner"),
                "NVDA",
                "COMMON_STOCK",
                locale,
                DataMode.MOCK,
                request
        );
    }

    List<StoredSource> sources() {
        return List.of(filing, fundamentals, market);
    }

    List<StoredQuantResult> quantResults() {
        return quantResults;
    }

    List<StoredEvidence> evidence() {
        return evidence;
    }

    JsonNode report() {
        return new DeterministicMockReportGenerator(objectMapper).generate(
                context(),
                sources(),
                quantResults(),
                evidence(),
                2
        );
    }

    private ObjectNode marketPayload() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("symbol", "NVDA");
        payload.put("periodStart", "2019-01-02");
        payload.put("periodEnd", AS_OF_DATE);
        ArrayNode prices = payload.putArray("prices");
        prices.addObject().put("date", AS_OF_DATE).put("adjustedClose", "100");
        return payload;
    }

    private ObjectNode fundamentalPayload() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("symbol", "NVDA");
        payload.put("asOfDate", AS_OF_DATE);
        ArrayNode metrics = payload.putArray("metrics");
        metric(metrics, "revenue", "1000", "USD");
        metric(metrics, "netDebt", "100", "USD");
        metric(metrics, "dilutedShares", "10", "SHARES");
        ArrayNode scenarios = payload.putArray("scenarios");
        scenario(scenarios, "BULL", "0.25", "0.3", "0.6", "20");
        scenario(scenarios, "BASE", "0.5", "0.2", "0.5", "15");
        scenario(scenarios, "BEAR", "0.25", "-0.1", "0.3", "8");
        payload.put("watermark", DeterministicMockReportGenerator.DEMO_WATERMARK);
        return payload;
    }

    private ObjectNode filingPayload() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("symbol", "NVDA");
        payload.put("asOfDate", AS_OF_DATE);
        payload.putArray("filings").addObject()
                .put("formType", "10-K")
                .put("summary", "Synthetic competition and supply risk disclosure.");
        return payload;
    }

    private StoredQuantResult quant(String metricName, String value, String unit) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("name", metricName);
        result.put("status", "AVAILABLE");
        result.put("value", value);
        result.put("unit", unit);
        return new StoredQuantResult(
                uuid("quant-" + metricName),
                "calc_" + metricName,
                metricName,
                new BigDecimal(value),
                unit,
                "AVAILABLE",
                result
        );
    }

    private StoredEvidence sourceEvidence(
            StoredSource source,
            String publicId,
            String title,
            JsonNode value
    ) {
        return new StoredEvidence(
                uuid("evidence-" + source.purpose()),
                publicId,
                switch (source.purpose()) {
                    case "MARKET_DATA", "BENCHMARK_DATA" -> "MARKET_PRICE";
                    case "FUNDAMENTALS" -> "FINANCIAL_METRIC";
                    case "FILING" -> "SEC_FILING";
                    case "MACRO" -> "MACRO_OBSERVATION";
                    default -> "COMPANY_PROFILE";
                },
                title,
                "Fixed local demo source.",
                value,
                null,
                source.id(),
                null
        );
    }

    private static void metric(ArrayNode metrics, String name, String value, String unit) {
        metrics.addObject()
                .put("name", name)
                .put("value", value)
                .put("unit", unit)
                .put("periodType", "POINT_IN_TIME")
                .put("periodEndDate", AS_OF_DATE);
    }

    private static void scenario(
            ArrayNode scenarios,
            String name,
            String probability,
            String growth,
            String margin,
            String multiple
    ) {
        scenarios.addObject()
                .put("name", name)
                .put("probability", probability)
                .put("revenueGrowth", growth)
                .put("targetEbitdaMargin", margin)
                .put("evToEbitdaMultiple", multiple);
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
