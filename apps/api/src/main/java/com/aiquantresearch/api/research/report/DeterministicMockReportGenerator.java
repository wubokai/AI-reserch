package com.aiquantresearch.api.research.report;

import com.aiquantresearch.api.research.orchestration.ResearchExecutionContext;
import com.aiquantresearch.api.research.orchestration.StoredEvidence;
import com.aiquantresearch.api.research.orchestration.StoredQuantResult;
import com.aiquantresearch.api.research.orchestration.StoredSource;
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
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class DeterministicMockReportGenerator {

    public static final String SCHEMA_VERSION = "research_report_v1";
    public static final String DEMO_WATERMARK = "DEMO DATA — NOT REAL MARKET DATA";
    public static final String DISCLAIMER = DEMO_WATERMARK
            + ". Research use only; not investment advice.";

    private static final List<String> SCENARIO_ORDER = List.of("BULL", "BASE", "BEAR");

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
                        + DEMO_WATERMARK
        );
        report.put("symbol", context.symbol());
        report.put("securityType", context.securityType());
        report.put("locale", context.locale());
        report.put("asOfDate", asOfDate);
        report.put("dataMode", context.dataMode().name());

        ArrayNode sections = report.putArray("sections");
        sections.add(section(
                "overview",
                chinese ? "研究范围" : "Research scope",
                List.of(claims.fact(
                        "overview_snapshot",
                        chinese
                                ? "本报告基于截至 " + asOfDate + " 的本地合成市场快照。"
                                : "This report uses a local synthetic market snapshot as of "
                                        + asOfDate + ".",
                        "MATERIAL",
                        marketEvidence
                )),
                chinese ? DEMO_WATERMARK + "；所有输入均为固定演示数据。"
                        : DEMO_WATERMARK + "; every input is a fixed demo fixture."
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
            sections.add(section(
                    "fundamentals",
                    chinese ? "基本面输入" : "Fundamental inputs",
                    List.of(claims.fact(
                            "fundamental_snapshot",
                            chinese
                                    ? "情景估值使用本地合成基本面及三组固定假设。"
                                    : "Scenario valuation uses local synthetic fundamentals and three fixed assumption sets.",
                            "MATERIAL",
                            fundamentalEvidence
                    )),
                    chinese ? "基本面仅用于演示计算链路。"
                            : "Fundamentals are provided only to demonstrate the calculation chain."
            ));
        }
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
        ArrayNode bearCase = report.putArray("bearCase");
        bearCase.add(claims.calculation(
                "bear_case_price",
                metricLabel(chinese, "悲观情景隐含价格", "Bear-case implied price"),
                bearPrice
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
        List<String> missingMetrics = new ArrayList<>(orderedQuant.stream()
                .filter(item -> !"AVAILABLE".equals(item.status()) || item.value() == null)
                .map(StoredQuantResult::metricName)
                .distinct()
                .sorted()
                .toList());
        if (!fundamentalRequested) {
            missingMetrics.add("fundamental_analysis: NOT_AVAILABLE (not requested)");
        }
        dataQuality.put(
                "score",
                BigDecimal.ONE.subtract(
                        BigDecimal.valueOf(Math.min(0.50, missingMetrics.size() * 0.05))
                )
        );
        ArrayNode missingData = dataQuality.putArray("missingData");
        missingMetrics.forEach(missingData::add);
        dataQuality.putArray("staleEvidenceIds");
        dataQuality.putArray("sourceConflicts");
        dataQuality.putArray("limitations")
                .add(DEMO_WATERMARK)
                .add(chinese
                        ? "固定演示数据不代表真实市场、公司或宏观状况。"
                        : "Fixed demo data does not represent real market, company, or macro conditions.");

        report.putArray("conclusion").add(claims.calculation(
                "conclusion_weighted_price",
                metricLabel(chinese, "研究结论的概率加权隐含价格", "Conclusion weighted implied price"),
                weightedPrice
        ));
        report.put(
                "disclaimer",
                chinese ? DISCLAIMER + " 本报告仅供研究演示，不构成投资建议。" : DISCLAIMER
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
        Map<String, JsonNode> assumptions = new LinkedHashMap<>();
        for (JsonNode assumption : fundamentals.payload().path("scenarios")) {
            assumptions.put(assumption.path("name").asText().toUpperCase(Locale.ROOT), assumption);
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
            JsonNode assumption = assumptions.get(scenarioName);
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
            scenario.put("probability", decimalField(assumption, "probability"));
            scenario.put("revenueGrowth", decimalField(assumption, "revenueGrowth"));
            scenario.put("targetEbitdaMargin", decimalField(assumption, "targetEbitdaMargin"));
            scenario.put("evToEbitdaMultiple", decimalField(assumption, "evToEbitdaMultiple"));
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

    private final class ClaimFactory {

        private final String idPrefix;
        private final Map<UUID, StoredEvidence> evidenceByQuantId;
        private final boolean chinese;

        private ClaimFactory(
                UUID researchId,
                int version,
                Map<UUID, StoredEvidence> evidenceByQuantId,
                boolean chinese
        ) {
            this.idPrefix = "cl_"
                    + researchId.toString().replace("-", "").substring(0, 10)
                    + "_v" + version + "_";
            this.evidenceByQuantId = evidenceByQuantId;
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
            return claim(
                    key,
                    statement,
                    "FACT",
                    materiality,
                    List.of(evidence.publicId()),
                    List.of(),
                    List.of()
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
                    List.of(reference)
            );
        }

        private ObjectNode claim(
                String key,
                String statement,
                String claimType,
                String materiality,
                List<String> evidenceIds,
                List<String> calculationIds,
                List<ObjectNode> numericReferences
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
            claim.put("confidence", "FACT".equals(claimType) ? 0.95 : 0.98);
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
