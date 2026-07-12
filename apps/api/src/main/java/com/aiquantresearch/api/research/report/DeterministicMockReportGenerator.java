package com.aiquantresearch.api.research.report;

import com.aiquantresearch.api.research.analytics.DeterministicScenarioPolicy;
import com.aiquantresearch.api.research.orchestration.ResearchExecutionContext;
import com.aiquantresearch.api.research.orchestration.StoredEvidence;
import com.aiquantresearch.api.research.orchestration.StoredQuantResult;
import com.aiquantresearch.api.research.orchestration.StoredSource;
import com.aiquantresearch.api.research.provider.ScenarioAssumption;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class DeterministicMockReportGenerator {

    public static final String SCHEMA_VERSION = "research_report_v1";
    public static final String DEMO_WATERMARK = "DEMO DATA — NOT REAL MARKET DATA";
    public static final String DISCLAIMER = DEMO_WATERMARK
            + ". Research use only; not investment advice.";
    public static final String REAL_DATA_LABEL = "REAL PROVIDER DATA";
    public static final String REAL_DISCLAIMER = REAL_DATA_LABEL
            + ". Research use only; not investment advice.";

    private static final List<String> SCENARIO_ORDER = List.of("BULL", "BASE", "BEAR");
    private static final Pattern ISO_DATE = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");

    private final ObjectMapper objectMapper;

    public DeterministicMockReportGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode generate(
            ResearchExecutionContext context,
            List<StoredSource> sources,
            List<StoredQuantResult> quantResults,
            List<StoredEvidence> evidence,
            int version
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(quantResults, "quantResults");
        Objects.requireNonNull(evidence, "evidence");
        if (version < 1) {
            throw new IllegalArgumentException("Report version must be positive");
        }
        if (!List.of("zh-CN", "en-US").contains(context.locale())) {
            throw new IllegalArgumentException("Unsupported report locale: " + context.locale());
        }
        boolean demoData = context.dataMode() == DataMode.MOCK;

        List<StoredSource> orderedSources = sources.stream()
                .sorted(Comparator.comparing(StoredSource::purpose)
                        .thenComparing(StoredSource::id))
                .toList();
        List<StoredQuantResult> orderedQuant = quantResults.stream()
                .sorted(Comparator.comparing(StoredQuantResult::metricName)
                        .thenComparing(StoredQuantResult::publicId))
                .toList();
        List<StoredEvidence> orderedEvidence = evidence.stream()
                .sorted(Comparator.comparing(StoredEvidence::publicId))
                .toList();

        Map<String, StoredQuantResult> quantByMetric = orderedQuant.stream()
                .filter(item -> "AVAILABLE".equals(item.status()) && item.value() != null)
                .collect(Collectors.toMap(
                        StoredQuantResult::metricName,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<UUID, StoredEvidence> evidenceByQuantId = orderedEvidence.stream()
                .filter(item -> item.quantResultId() != null)
                .collect(Collectors.toMap(
                        StoredEvidence::quantResultId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<UUID, StoredEvidence> evidenceBySourceId = orderedEvidence.stream()
                .filter(item -> item.sourceSnapshotId() != null)
                .collect(Collectors.toMap(
                        StoredEvidence::sourceSnapshotId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<String, StoredEvidence> evidenceByPublicId = orderedEvidence.stream()
                .collect(Collectors.toMap(
                        StoredEvidence::publicId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        boolean fundamentalRequested = context.request()
                .path("includeFundamentalAnalysis")
                .asBoolean(true);
        StoredSource market = requireSource(orderedSources, "MARKET_DATA");
        StoredSource fundamentals = findSource(orderedSources, "FUNDAMENTALS");
        StoredEvidence marketEvidence = requireSourceEvidence(market, evidenceBySourceId);
        StoredEvidence fundamentalEvidence = null;
        if (fundamentalRequested) {
            if (fundamentals == null) {
                throw new IllegalArgumentException(
                        "Required source is unavailable: FUNDAMENTALS"
                );
            }
            fundamentalEvidence = requireSourceEvidence(fundamentals, evidenceBySourceId);
        }
        StoredEvidence contextualEvidence = orderedSources.stream()
                .filter(item -> List.of("FILING", "MACRO", "SECURITY_PROFILE")
                        .contains(item.purpose()))
                .map(item -> evidenceBySourceId.get(item.id()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(marketEvidence);

        String asOfDate = determineAsOfDate(context, orderedSources, orderedEvidence);
        boolean chinese = "zh-CN".equals(context.locale());
        ClaimFactory claims = new ClaimFactory(
                context.researchId(),
                version,
                evidenceByQuantId,
                evidenceByPublicId,
                chinese
        );

        StoredQuantResult totalReturn = requireMetric(quantByMetric, "total_return");
        StoredQuantResult cagr = requireMetric(quantByMetric, "cagr");
        StoredQuantResult volatility = requireMetric(quantByMetric, "annualized_volatility");
        StoredQuantResult maxDrawdown = requireMetric(quantByMetric, "max_drawdown");
        StoredQuantResult sharpe = requireMetric(quantByMetric, "sharpe_ratio");
        StoredQuantResult bullPrice = requireMetric(quantByMetric, "scenario_bull_implied_price");
        StoredQuantResult basePrice = requireMetric(quantByMetric, "scenario_base_implied_price");
        StoredQuantResult bearPrice = requireMetric(quantByMetric, "scenario_bear_implied_price");
        StoredQuantResult weightedPrice = requireMetric(quantByMetric, "weighted_scenario_value");
        if (fundamentals == null) {
            throw new IllegalArgumentException(
                    "Scenario source inputs are unavailable for the existing scenario calculations"
            );
        }

        ObjectNode report = objectMapper.createObjectNode();
        report.put("schemaVersion", SCHEMA_VERSION);
        report.put(
                "title",
                context.symbol() + (chinese ? " 证据驱动研究报告 — " : " evidence-backed report — ")
                        + (demoData ? DEMO_WATERMARK : REAL_DATA_LABEL)
        );
        report.put("symbol", context.symbol());
        report.put("securityType", context.securityType());
        report.put("locale", context.locale());
        report.put("asOfDate", asOfDate);
        report.put("dataMode", context.dataMode().name());

        ArrayNode sections = report.putArray("sections");
        sections.add(section(
                "executive_summary",
                chinese ? "执行摘要" : "Executive Summary",
                List.of(
                        claims.fact(
                                "overview_snapshot",
                                chinese
                                        ? demoData
                                                ? "本报告基于截至 " + asOfDate + " 的本地合成市场快照。"
                                                : "本报告基于截至 " + asOfDate + " 的已注册真实数据源快照。"
                                        : demoData
                                                ? "This report uses a local synthetic market snapshot as of "
                                                        + asOfDate + "."
                                                : "This report uses registered real-provider snapshots as of "
                                                        + asOfDate + ".",
                                "MATERIAL",
                                marketEvidence
                        ),
                        claims.calculation(
                                "executive_total_return",
                                metricLabel(chinese, "历史总回报", "Historical total return"),
                                totalReturn
                        ),
                        claims.calculation(
                                "executive_max_drawdown",
                                metricLabel(chinese, "历史最大回撤", "Historical maximum drawdown"),
                                maxDrawdown
                        )
                ),
                chinese
                        ? (demoData ? DEMO_WATERMARK : REAL_DATA_LABEL)
                                + "；结论置信度由证据质量规则确定。"
                        : (demoData ? DEMO_WATERMARK : REAL_DATA_LABEL)
                                + "; confidence is derived from the evidence-quality policy."
        ));
        sections.add(section(
                "company_overview",
                chinese ? "公司概览" : "Company Overview",
                List.of(claims.fact(
                        "company_registered_context",
                        chinese
                                ? "证券主数据、基本面和披露文件已作为本次研究的可追溯上下文注册。"
                                : "Security-master, fundamental, and filing evidence are registered as traceable context for this research.",
                        "MATERIAL",
                        contextualEvidence
                )),
                chinese
                        ? "商业模式、收入驱动与竞争信息仅可来自注册披露；当前无可验证关键客户清单，因此不作断言。"
                        : "Business model, revenue drivers, and competition may use only registered filings; no verified key-customer list is available, so none is asserted."
        ));
        sections.add(section(
                "performance",
                chinese ? "收益表现" : "Performance",
                List.of(
                        claims.calculation("section_total_return", metricLabel(chinese, "总回报", "Total return"), totalReturn),
                        claims.calculation("section_cagr", metricLabel(chinese, "年复合增长率", "CAGR"), cagr)
                ),
                chinese ? "收益指标来自确定性 quant_v1 计算。"
                        : "Return metrics come from deterministic quant_v1 calculations."
        ));
        sections.add(section(
                "risk",
                chinese ? "风险画像" : "Risk profile",
                List.of(
                        claims.calculation("section_volatility", metricLabel(chinese, "年化波动率", "Annualized volatility"), volatility),
                        claims.calculation("section_drawdown", metricLabel(chinese, "最大回撤", "Maximum drawdown"), maxDrawdown),
                        claims.calculation("section_sharpe", metricLabel(chinese, "夏普比率", "Sharpe ratio"), sharpe)
                ),
                chinese ? "历史合成序列不能预测未来风险。"
                        : "The synthetic history does not predict future risk."
        ));
        if (fundamentalRequested) {
            List<ObjectNode> financialClaims = calculationClaims(
                    claims,
                    quantByMetric,
                    "financial",
                    List.of(
                            new MetricDisplay("revenue_growth_yoy", "收入同比增长", "Revenue growth YoY"),
                            new MetricDisplay("gross_margin", "毛利率", "Gross margin"),
                            new MetricDisplay("operating_margin", "营业利润率", "Operating margin"),
                            new MetricDisplay("free_cash_flow_margin", "自由现金流率", "Free-cash-flow margin"),
                            new MetricDisplay("net_debt", "净债务", "Net debt"),
                            new MetricDisplay("share_dilution", "股本稀释", "Share dilution"),
                            new MetricDisplay("capex_trend_slope", "资本开支趋势", "Capital-expenditure trend")
                    )
            );
            financialClaims.addFirst(claims.fact(
                    "fundamental_snapshot",
                    chinese
                            ? "财务分析仅使用已注册的规范化基本面快照和确定性计算。"
                            : "Financial analysis uses only the registered normalized fundamental snapshot and deterministic calculations.",
                    "MATERIAL",
                    fundamentalEvidence
            ));
            sections.add(section(
                    "financial_analysis",
                    chinese ? "财务分析" : "Financial Analysis",
                    financialClaims,
                    chinese ? "收入、利润率、现金流、资产负债表、资本开支和稀释仅展示可计算项。"
                            : "Revenue, margins, cash flow, balance sheet, capex, and dilution are shown only when calculable."
            ));
        }
        sections.add(section(
                "technical_analysis",
                chinese ? "技术与市场结构" : "Technical and Market Structure",
                calculationClaims(
                        claims,
                        quantByMetric,
                        "technical",
                        List.of(
                                new MetricDisplay(
                                        "rsi_14",
                                        "十四期相对强弱指标",
                                        "Fourteen-period relative-strength index"
                                ),
                                new MetricDisplay("macd", "MACD", "MACD"),
                                new MetricDisplay("macd_signal", "MACD 信号线", "MACD signal"),
                                new MetricDisplay(
                                        "atr_14",
                                        "十四期平均真实波幅",
                                        "Fourteen-period average true range"
                                ),
                                new MetricDisplay(
                                        "distance_from_52_week_high",
                                        "距滚动一年高点",
                                        "Distance from rolling-year high"
                                ),
                                new MetricDisplay(
                                        "volume_moving_average_20",
                                        "二十交易日成交量均值",
                                        "Twenty-session average volume"
                                ),
                                new MetricDisplay("trend_score", "趋势评分", "Trend score")
                        )
                ),
                chinese ? "趋势分类和成交量结论来自确定性规则；样本不足时留空并在限制中说明。"
                        : "Trend and volume conclusions use deterministic rules; insufficient metrics remain omitted and disclosed."
        ));
        List<ObjectNode> valuationClaims = calculationClaims(
                claims,
                quantByMetric,
                "valuation",
                List.of(
                        new MetricDisplay("market_capitalization", "市值", "Market capitalization"),
                        new MetricDisplay("price_to_earnings", "市盈率", "Price to earnings"),
                        new MetricDisplay("price_to_sales", "市销率", "Price to sales"),
                        new MetricDisplay("price_to_book", "市净率", "Price to book"),
                        new MetricDisplay("enterprise_value_to_ebitda", "EV/EBITDA", "EV to EBITDA"),
                        new MetricDisplay("free_cash_flow_yield", "自由现金流收益率", "Free-cash-flow yield")
                )
        );
        valuationClaims.add(claims.calculation(
                "valuation_weighted_scenario",
                metricLabel(chinese, "概率加权情景价值", "Probability-weighted scenario value"),
                weightedPrice
        ));
        sections.add(section(
                "valuation",
                chinese ? "估值" : "Valuation",
                valuationClaims,
                chinese ? "历史估值与同行估值数据不可用；情景结果不是确定目标价。"
                        : "Historical and peer valuation data are unavailable; scenario outputs are not deterministic price targets."
        ));
        sections.add(section(
                "scenario",
                chinese ? "情景估值" : "Scenario valuation",
                List.of(claims.calculation(
                        "section_weighted_price",
                        metricLabel(chinese, "概率加权隐含价格", "Probability-weighted implied price"),
                        weightedPrice
                )),
                chinese ? "加权值由 BULL、BASE、BEAR 三种情景合成。"
                        : "The weighted value combines BULL, BASE, and BEAR scenarios."
        ));

        ArrayNode bullCase = report.putArray("bullCase");
        bullCase.add(claims.calculation(
                "bull_case_price",
                metricLabel(chinese, "乐观情景隐含价格", "Bull-case implied price"),
                bullPrice
        ));
        if (cagr.value().signum() > 0) {
            bullCase.add(claims.calculation(
                    "bull_case_cagr",
                    metricLabel(chinese, "正向历史 CAGR 证据", "Positive historical CAGR evidence"),
                    cagr
            ));
        }
        if (totalReturn.value().signum() > 0) {
            bullCase.add(claims.calculation(
                    "bull_case_total_return",
                    metricLabel(chinese, "正向历史总回报证据", "Positive historical total-return evidence"),
                    totalReturn
            ));
        }
        ArrayNode bearCase = report.putArray("bearCase");
        bearCase.add(claims.calculation(
                "bear_case_price",
                metricLabel(chinese, "悲观情景隐含价格", "Bear-case implied price"),
                bearPrice
        ));
        bearCase.add(claims.calculation(
                "bear_case_drawdown",
                metricLabel(chinese, "历史最大回撤风险", "Historical maximum-drawdown risk"),
                maxDrawdown
        ));
        bearCase.add(claims.calculation(
                "bear_case_volatility",
                metricLabel(chinese, "历史年化波动风险", "Historical annualized-volatility risk"),
                volatility
        ));
        ArrayNode catalysts = report.putArray("catalysts");
        catalysts.add(claims.fact(
                "catalyst_context",
                chinese
                        ? "合成披露与宏观快照提供了后续验证情景驱动因素的上下文。"
                        : "Synthetic filing and macro snapshots provide context for validating scenario drivers.",
                "SUPPORTING",
                contextualEvidence
        ));
        ArrayNode risks = report.putArray("risks");
        ObjectNode marketRisk = risks.addObject();
        marketRisk.put("category", "MARKET");
        marketRisk.set("claim", claims.calculation(
                "risk_drawdown",
                metricLabel(chinese, "合成历史最大回撤", "Synthetic-history maximum drawdown"),
                maxDrawdown
        ));
        addEvidenceRisk(
                risks,
                "BUSINESS",
                "risk_business_context",
                chinese ? "业务风险结论受限于已注册披露文件。"
                        : "Business-risk conclusions are bounded by the registered filing evidence.",
                contextualEvidence,
                claims
        );
        addEvidenceRisk(
                risks,
                "FINANCIAL",
                "risk_financial_context",
                chinese ? "财务风险结论仅使用规范化财务证据和确定性指标。"
                        : "Financial-risk conclusions use only normalized financial evidence and deterministic metrics.",
                fundamentalEvidence == null ? contextualEvidence : fundamentalEvidence,
                claims
        );
        addEvidenceRisk(
                risks,
                "REGULATORY",
                "risk_regulatory_context",
                chinese ? "未注册的监管事件不进入本报告。"
                        : "Regulatory events absent from registered evidence are not asserted.",
                contextualEvidence,
                claims
        );
        addEvidenceRisk(
                risks,
                "EXECUTION",
                "risk_execution_context",
                chinese ? "执行风险仅依据已注册公司披露进行定性限制。"
                        : "Execution risk is qualitatively bounded to registered company disclosures.",
                contextualEvidence,
                claims
        );
        ObjectNode valuationRisk = risks.addObject();
        valuationRisk.put("category", "VALUATION");
        valuationRisk.set("claim", claims.calculation(
                "risk_bear_valuation",
                metricLabel(chinese, "悲观情景估值", "Bear-scenario valuation"),
                bearPrice
        ));
        addEvidenceRisk(
                risks,
                "DATA_QUALITY",
                "risk_data_quality",
                chinese ? "固定演示数据不能代表当前真实市场。"
                        : "Fixed demo data cannot represent current real-market conditions.",
                marketEvidence,
                claims
        );

        report.set(
                "scenarioAnalysis",
                scenarioAnalysis(
                        fundamentals,
                        quantByMetric,
                        claims,
                        bullPrice,
                        basePrice,
                        bearPrice,
                        weightedPrice
                )
        );

        ObjectNode dataQuality = report.putObject("dataQuality");
        var qualityAssessment = EvidenceScoringPolicy.dataQuality(
                context,
                orderedEvidence,
                orderedQuant,
                List.of()
        );
        List<String> optionalUnavailableMetrics = orderedQuant.stream()
                .filter(item -> !"AVAILABLE".equals(item.status()) || item.value() == null)
                .map(StoredQuantResult::metricName)
                .distinct()
                .sorted()
                .toList();
        dataQuality.put("score", qualityAssessment.score());
        ArrayNode missingData = dataQuality.putArray("missingData");
        qualityAssessment.missingData().forEach(missingData::add);
        ArrayNode staleEvidenceIds = dataQuality.putArray("staleEvidenceIds");
        qualityAssessment.staleEvidenceIds().forEach(staleEvidenceIds::add);
        dataQuality.putArray("sourceConflicts");
        ArrayNode limitations = dataQuality.putArray("limitations");
        if (demoData) {
            limitations
                    .add(DEMO_WATERMARK)
                    .add(chinese
                            ? "固定演示数据不代表真实市场、公司或宏观状况。"
                            : "Fixed demo data does not represent real market, company, or macro conditions.");
        } else {
            limitations
                    .add(chinese
                            ? "数据来自已注册的真实外部数据源，仍可能存在延迟、修订或覆盖缺口。"
                            : "Registered real-provider data may still have delays, revisions, or coverage gaps.")
                    .add(chinese
                            ? "Bull/Base/Bear 使用 deterministic_scenario_policy_v1 固定透明假设，仅用于敏感性分析，不是价格预测。"
                            : "Bull/Base/Bear uses transparent deterministic_scenario_policy_v1 assumptions for sensitivity analysis, not a price forecast.");
        }
        optionalUnavailableMetrics.stream()
                .limit(28)
                .map(name -> "Optional quant metric unavailable: " + name)
                .forEach(limitations::add);
        limitations.add(chinese
                ? "缺少可验证的关键客户清单，不作客户集中度事实断言。"
                : "No verified key-customer list is available; no customer-concentration fact is asserted.");
        limitations.add(chinese
                ? "缺少历史估值序列和同行估值数据。"
                : "Historical valuation series and peer valuation data are unavailable.");

        report.putArray("conclusion").add(claims.calculation(
                "conclusion_weighted_price",
                metricLabel(chinese, "研究结论的概率加权隐含价格", "Conclusion weighted implied price"),
                weightedPrice
        ));
        report.put(
                "disclaimer",
                demoData
                        ? chinese
                                ? DISCLAIMER + " 本报告仅供研究演示，不构成投资建议。"
                                : DISCLAIMER
                        : chinese
                                ? REAL_DISCLAIMER + " 本报告仅供个人研究，不构成投资建议。"
                                : REAL_DISCLAIMER
        );
        return report;
    }

    private ObjectNode scenarioAnalysis(
            StoredSource fundamentals,
            Map<String, StoredQuantResult> quantByMetric,
            ClaimFactory claims,
            StoredQuantResult bullPrice,
            StoredQuantResult basePrice,
            StoredQuantResult bearPrice,
            StoredQuantResult weightedPrice
    ) {
        Map<String, ScenarioAssumption> assumptions = new LinkedHashMap<>();
        for (JsonNode assumption : fundamentals.payload().path("scenarios")) {
            ScenarioAssumption parsed = new ScenarioAssumption(
                    assumption.path("name").asText().toUpperCase(Locale.ROOT),
                    decimalValue(assumption.path("revenueGrowth"), "scenario revenue growth"),
                    decimalValue(assumption.path("targetEbitdaMargin"), "scenario EBITDA margin"),
                    decimalValue(assumption.path("evToEbitdaMultiple"), "scenario multiple"),
                    decimalValue(assumption.path("probability"), "scenario probability")
            );
            assumptions.put(parsed.name(), parsed);
        }
        if (assumptions.isEmpty()) {
            BigDecimal revenue = fundamentalMetric(fundamentals.payload(), "revenue");
            BigDecimal profit = optionalFundamentalMetric(fundamentals.payload(), "ebitda");
            if (profit == null) {
                profit = optionalFundamentalMetric(fundamentals.payload(), "operatingIncome");
            }
            DeterministicScenarioPolicy.create(revenue, profit)
                    .forEach(item -> assumptions.put(item.name(), item));
        }
        if (!assumptions.keySet().containsAll(SCENARIO_ORDER) || assumptions.size() != 3) {
            throw new IllegalArgumentException("Exactly BULL, BASE, and BEAR assumptions are required");
        }
        BigDecimal dilutedShares = fundamentalMetric(fundamentals.payload(), "dilutedShares");

        ObjectNode analysis = objectMapper.createObjectNode();
        analysis.put("calculationId", weightedPrice.publicId());
        ArrayNode scenarios = analysis.putArray("scenarios");
        Map<String, StoredQuantResult> displayedPrice = Map.of(
                "BULL", bullPrice,
                "BASE", basePrice,
                "BEAR", bearPrice
        );
        for (String scenarioName : SCENARIO_ORDER) {
            String lowerName = scenarioName.toLowerCase(Locale.ROOT);
            ScenarioAssumption assumption = assumptions.get(scenarioName);
            StoredQuantResult rawPrice = requireMetric(
                    quantByMetric,
                    "scenario_" + lowerName + "_raw_implied_price"
            );
            StoredQuantResult upside = requireMetric(
                    quantByMetric,
                    "scenario_" + lowerName + "_upside_downside"
            );
            ObjectNode scenario = scenarios.addObject();
            scenario.put("name", scenarioName);
            scenario.put("probability", decimal(assumption.probability()));
            scenario.put("revenueGrowth", decimal(assumption.revenueGrowth()));
            scenario.put("targetEbitdaMargin", decimal(assumption.targetEbitdaMargin()));
            scenario.put("evToEbitdaMultiple", decimal(assumption.evToEbitdaMultiple()));
            scenario.put("impliedEquityValue", decimal(rawPrice.value().multiply(dilutedShares)));
            scenario.put("impliedPrice", decimal(displayedPrice.get(scenarioName).value()));
            scenario.put("upsideDownside", decimal(upside.value()));
        }
        analysis.put("weightedImpliedPrice", decimal(weightedPrice.value()));
        ArrayNode summaryClaims = analysis.putArray("summaryClaims");
        summaryClaims.add(claims.calculation(
                "scenario_summary_bull",
                metricLabel(claims.chinese(), "BULL 情景隐含价格", "BULL scenario implied price"),
                bullPrice
        ));
        summaryClaims.add(claims.calculation(
                "scenario_summary_base",
                metricLabel(claims.chinese(), "BASE 情景隐含价格", "BASE scenario implied price"),
                basePrice
        ));
        summaryClaims.add(claims.calculation(
                "scenario_summary_bear",
                metricLabel(claims.chinese(), "BEAR 情景隐含价格", "BEAR scenario implied price"),
                bearPrice
        ));
        summaryClaims.add(claims.calculation(
                "scenario_summary_weighted",
                metricLabel(claims.chinese(), "概率加权情景价值", "Probability-weighted scenario value"),
                weightedPrice
        ));
        return analysis;
    }

    private List<ObjectNode> calculationClaims(
            ClaimFactory claims,
            Map<String, StoredQuantResult> quantByMetric,
            String keyPrefix,
            List<MetricDisplay> metrics
    ) {
        List<ObjectNode> result = new ArrayList<>();
        for (MetricDisplay display : metrics) {
            StoredQuantResult quant = quantByMetric.get(display.name());
            if (quant != null) {
                result.add(claims.calculation(
                        keyPrefix + "_" + display.name(),
                        metricLabel(claims.chinese(), display.zh(), display.en()),
                        quant
                ));
            }
        }
        return result;
    }

    private void addEvidenceRisk(
            ArrayNode risks,
            String category,
            String key,
            String statement,
            StoredEvidence evidence,
            ClaimFactory claims
    ) {
        ObjectNode risk = risks.addObject();
        risk.put("category", category);
        risk.set("claim", claims.fact(key, statement, "MATERIAL", evidence));
    }

    private ObjectNode section(
            String id,
            String heading,
            List<ObjectNode> claims,
            String transitionText
    ) {
        ObjectNode section = objectMapper.createObjectNode();
        section.put("id", id);
        section.put("heading", heading);
        ArrayNode claimArray = section.putArray("claims");
        claims.forEach(claimArray::add);
        section.put("transitionText", transitionText);
        return section;
    }

    private static StoredSource requireSource(List<StoredSource> sources, String purpose) {
        StoredSource source = findSource(sources, purpose);
        if (source == null) {
            throw new IllegalArgumentException("Required source is unavailable: " + purpose);
        }
        return source;
    }

    private static StoredSource findSource(List<StoredSource> sources, String purpose) {
        return sources.stream()
                .filter(item -> purpose.equals(item.purpose()))
                .findFirst()
                .orElse(null);
    }

    private static StoredEvidence requireSourceEvidence(
            StoredSource source,
            Map<UUID, StoredEvidence> evidenceBySourceId
    ) {
        StoredEvidence item = evidenceBySourceId.get(source.id());
        if (item == null) {
            throw new IllegalArgumentException(
                    "Evidence is unavailable for source: " + source.purpose()
            );
        }
        return item;
    }

    private static StoredQuantResult requireMetric(
            Map<String, StoredQuantResult> quantByMetric,
            String name
    ) {
        StoredQuantResult metric = quantByMetric.get(name);
        if (metric == null) {
            throw new IllegalArgumentException("Required calculation is unavailable: " + name);
        }
        return metric;
    }

    private static BigDecimal fundamentalMetric(JsonNode payload, String name) {
        JsonNode metrics = payload.path("metrics");
        if (metrics.isArray()) {
            for (JsonNode metric : metrics) {
                if (name.equals(metric.path("name").asText())) {
                    return decimalValue(metric.path("value"), "fundamental metric " + name);
                }
            }
        } else if (metrics.isObject() && metrics.has(name)) {
            return decimalValue(metrics.path(name), "fundamental metric " + name);
        }
        throw new IllegalArgumentException("Required fundamental metric is unavailable: " + name);
    }

    private static BigDecimal optionalFundamentalMetric(JsonNode payload, String name) {
        try {
            return fundamentalMetric(payload, name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String decimalField(JsonNode node, String field) {
        return decimal(decimalValue(node.path(field), "scenario field " + field));
    }

    private static BigDecimal decimalValue(JsonNode node, String label) {
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid decimal for " + label, exception);
        }
    }

    private static String determineAsOfDate(
            ResearchExecutionContext context,
            List<StoredSource> sources,
            List<StoredEvidence> evidence
    ) {
        List<String> candidates = new ArrayList<>();
        sources.stream()
                .filter(item -> "MARKET_DATA".equals(item.purpose()))
                .map(item -> item.payload().path("periodEnd").asText())
                .filter(value -> !value.isBlank())
                .forEach(candidates::add);
        sources.stream()
                .map(item -> item.payload().path("asOfDate").asText())
                .filter(value -> !value.isBlank())
                .forEach(candidates::add);
        evidence.stream()
                .map(item -> item.value().path("asOfDate").asText())
                .filter(value -> !value.isBlank())
                .forEach(candidates::add);
        for (String requestField : List.of("asOfDate", "periodEnd", "endDate")) {
            String value = context.request().path(requestField).asText();
            if (!value.isBlank()) {
                candidates.add(value);
            }
        }
        return candidates.stream()
                .map(DeterministicMockReportGenerator::parseDate)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .map(LocalDate::toString)
                .orElseThrow(() -> new IllegalArgumentException("A valid report as-of date is required"));
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String decimal(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }
        return normalized.toPlainString();
    }

    private static String metricLabel(boolean chinese, String zh, String en) {
        return chinese ? zh : en;
    }

    private record MetricDisplay(String name, String zh, String en) {
    }

    private final class ClaimFactory {

        private final String idPrefix;
        private final Map<UUID, StoredEvidence> evidenceByQuantId;
        private final Map<String, StoredEvidence> evidenceByPublicId;
        private final boolean chinese;

        private ClaimFactory(
                UUID researchId,
                int version,
                Map<UUID, StoredEvidence> evidenceByQuantId,
                Map<String, StoredEvidence> evidenceByPublicId,
                boolean chinese
        ) {
            this.idPrefix = "cl_"
                    + researchId.toString().replace("-", "").substring(0, 10)
                    + "_v" + version + "_";
            this.evidenceByQuantId = evidenceByQuantId;
            this.evidenceByPublicId = evidenceByPublicId;
            this.chinese = chinese;
        }

        private boolean chinese() {
            return chinese;
        }

        private ObjectNode fact(
                String key,
                String statement,
                String materiality,
                StoredEvidence evidence
        ) {
            List<ObjectNode> dateReferences = new ArrayList<>();
            Matcher dates = ISO_DATE.matcher(statement);
            while (dates.find()) {
                String token = dates.group();
                if (token.equals(evidence.value().path("asOfDate").asText())) {
                    ObjectNode reference = objectMapper.createObjectNode();
                    reference.put("token", token);
                    reference.put("normalizedDate", token);
                    reference.put("sourceKind", "EVIDENCE");
                    reference.put("sourceId", evidence.publicId());
                    reference.put("jsonPointer", "/asOfDate");
                    dateReferences.add(reference);
                }
            }
            return claim(
                    key,
                    statement,
                    "FACT",
                    materiality,
                    List.of(evidence.publicId()),
                    List.of(),
                    List.of(),
                    dateReferences
            );
        }

        private ObjectNode calculation(
                String key,
                String label,
                StoredQuantResult quant
        ) {
            StoredEvidence quantEvidence = evidenceByQuantId.get(quant.id());
            if (quantEvidence == null) {
                throw new IllegalArgumentException(
                        "Evidence is unavailable for calculation: " + quant.metricName()
                );
            }
            String token = decimal(quant.value());
            String unit = quant.unit() == null || quant.unit().isBlank()
                    ? "NUMBER"
                    : quant.unit();
            String statement = chinese
                    ? label + "为 " + token + " " + unit + "。"
                    : label + " is " + token + " " + unit + ".";
            ObjectNode reference = objectMapper.createObjectNode();
            reference.put("token", token);
            reference.put("normalizedValue", token);
            reference.put("unit", unit);
            reference.put("sourceKind", "CALCULATION");
            reference.put("sourceId", quant.publicId());
            reference.put("jsonPointer", "/value");
            reference.put("tolerance", "0.00000001");
            return claim(
                    key,
                    statement,
                    "CALCULATION",
                    "MATERIAL",
                    List.of(quantEvidence.publicId()),
                    List.of(quant.publicId()),
                    List.of(reference),
                    List.of()
            );
        }

        private ObjectNode claim(
                String key,
                String statement,
                String claimType,
                String materiality,
                List<String> evidenceIds,
                List<String> calculationIds,
                List<ObjectNode> numericReferences,
                List<ObjectNode> dateReferences
        ) {
            ObjectNode claim = objectMapper.createObjectNode();
            claim.put("id", claimId(key));
            claim.put("statement", statement);
            claim.put("claimType", claimType);
            claim.put("materiality", materiality);
            ArrayNode evidenceArray = claim.putArray("evidenceIds");
            evidenceIds.forEach(evidenceArray::add);
            ArrayNode calculationArray = claim.putArray("calculationIds");
            calculationIds.forEach(calculationArray::add);
            ArrayNode referenceArray = claim.putArray("numericReferences");
            numericReferences.forEach(referenceArray::add);
            ArrayNode dateReferenceArray = claim.putArray("dateReferences");
            dateReferences.forEach(dateReferenceArray::add);
            List<StoredEvidence> supportingEvidence = evidenceIds.stream()
                    .map(evidenceByPublicId::get)
                    .filter(Objects::nonNull)
                    .toList();
            claim.put(
                    "confidence",
                    EvidenceScoringPolicy.claimConfidence(
                            claimType,
                            supportingEvidence,
                            false
                    )
            );
            claim.putArray("limitations");
            return claim;
        }

        private String claimId(String key) {
            String normalized = key.replaceAll("[^A-Za-z0-9_-]", "_");
            int available = 67 - idPrefix.length();
            if (normalized.length() > available) {
                normalized = normalized.substring(0, available);
            }
            return idPrefix + normalized;
        }
    }
}
