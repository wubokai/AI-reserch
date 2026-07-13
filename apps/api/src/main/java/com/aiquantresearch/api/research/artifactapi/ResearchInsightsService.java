package com.aiquantresearch.api.research.artifactapi;

import com.aiquantresearch.api.research.analytics.AnalyticsClient;
import com.aiquantresearch.api.research.application.CanonicalHashService;
import com.aiquantresearch.api.research.application.ResearchNotFoundException;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ResearchInsightsService {

    private static final String PUBLISHED = "('PASSED', 'PASSED_WITH_WARNINGS')";
    private static final String PRICE_METHODOLOGY =
            "价格为本次研究绑定的日线复权收盘价；MA20/MA50 和区间统计由 Analytics insights_v1 确定性计算。";
    private static final String PEER_METHODOLOGY =
            "同行组由业务类别清单确定；仅比较当前账户中已完成、同数据模式的研究报告，不补造缺失数据。";
    private static final Map<String, PeerGroup> PEER_GROUPS = peerGroups();

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CanonicalHashService hashService;
    private final AnalyticsClient analyticsClient;

    public ResearchInsightsService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            CanonicalHashService hashService,
            AnalyticsClient analyticsClient
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.hashService = hashService;
        this.analyticsClient = analyticsClient;
    }

    public ResearchInsightsResponse insights(UUID ownerId, UUID researchId, int reportVersion) {
        ReportContext report = requireReport(ownerId, researchId, reportVersion);
        List<MarketRow> marketRows = marketRows(researchId, report.report());
        JsonNode analytics = analyticsClient.runInsights(
                analyticsRequest(researchId, report.report(), marketRows)
        );
        return new ResearchInsightsResponse(
                researchId,
                reportVersion,
                report.dataMode(),
                priceChart(report.report(), marketRows, analytics),
                valuation(report.report(), analytics.path("valuation")),
                peers(ownerId, report)
        );
    }

    private ReportContext requireReport(UUID ownerId, UUID researchId, int version) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select r.id as research_id, r.data_mode, rv.version,
                       rv.data_as_of_date, rv.report_json::text as report_json
                  from research_jobs r
                  join report_versions rv on rv.research_job_id = r.id
                 where r.id = ? and r.user_id = ? and r.deleted_at is null
                   and r.data_mode <> 'MIXED_TEST' and rv.data_mode <> 'MIXED_TEST'
                   and rv.version = ? and rv.validation_status in %s
                """.formatted(PUBLISHED), researchId, ownerId, version);
        if (rows.isEmpty()) {
            throw new ResearchNotFoundException(researchId);
        }
        Map<String, Object> row = rows.getFirst();
        return new ReportContext(
                researchId,
                version,
                DataMode.valueOf(text(row, "data_mode")),
                date(row, "data_as_of_date"),
                json(text(row, "report_json"))
        );
    }

    private List<MarketRow> marketRows(UUID researchId, JsonNode report) {
        String symbol = report.path("symbol").asText();
        return jdbc.queryForList("""
                select observation_date, open, high, low, close, adjusted_close,
                       volume, provider, retrieved_at, source_snapshot_id
                  from market_price_bars
                 where research_job_id = ? and symbol = ?
                 order by observation_date
                """, researchId, symbol).stream().map(row -> new MarketRow(
                date(row, "observation_date"),
                decimal(row, "open"),
                decimal(row, "high"),
                decimal(row, "low"),
                decimal(row, "close"),
                decimal(row, "adjusted_close"),
                longValue(row, "volume"),
                text(row, "provider"),
                instant(row, "retrieved_at"),
                uuid(row, "source_snapshot_id")
        )).toList();
    }

    private ObjectNode analyticsRequest(
            UUID researchId,
            JsonNode report,
            List<MarketRow> marketRows
    ) {
        if (marketRows.size() < 2) {
            throw new IllegalStateException("Insight analytics requires at least two price bars");
        }
        MetricInput revenue = requiredFinancialMetric(researchId, "revenue");
        MetricInput netDebt = requiredFinancialMetric(researchId, "netDebt");
        MetricInput dilutedShares = requiredFinancialMetric(researchId, "dilutedShares");
        ObjectNode request = objectMapper.createObjectNode();
        request.put("schemaVersion", "analytics_full_request_v1");
        request.put("calculationVersion", "quant_v1");
        request.put("inputHash", "0".repeat(64));
        request.put("symbol", report.path("symbol").asText());
        request.put("securityType", report.path("securityType").asText("COMMON_STOCK"));
        request.put("periodStart", marketRows.getFirst().date().toString());
        request.put("periodEnd", marketRows.getLast().date().toString());
        ArrayNode prices = request.putArray("prices");
        for (MarketRow row : marketRows) {
            ObjectNode item = prices.addObject();
            item.put("date", row.date().toString());
            item.put("open", plain(row.open()));
            item.put("high", plain(row.high()));
            item.put("low", plain(row.low()));
            item.put("close", plain(row.close()));
            item.put("adjustedClose", plain(row.adjustedClose()));
            item.put("volume", Long.toString(row.volume()));
            item.put("sourceSnapshotId", row.sourceSnapshotId().toString());
        }
        request.putNull("benchmarkSymbol");
        request.putArray("benchmarkPrices");
        request.put("riskFreeRateAnnual", "0");
        request.put("minimumAcceptedReturnAnnual", "0");
        request.putArray("fundamentals");
        request.set(
                "scenarioInput",
                scenarioInput(report, marketRows.getLast(), revenue, netDebt, dilutedShares)
        );
        ObjectNode hashable = request.deepCopy();
        hashable.remove("inputHash");
        request.put("inputHash", hashService.hash(hashable));
        return request;
    }

    private ObjectNode scenarioInput(
            JsonNode report,
            MarketRow latestMarket,
            MetricInput revenue,
            MetricInput netDebt,
            MetricInput dilutedShares
    ) {
        JsonNode analysis = report.path("scenarioAnalysis");
        ObjectNode input = objectMapper.createObjectNode();
        input.put("baseRevenue", plain(revenue.value()));
        input.put("currentPrice", analysis.path("currentPrice").asText(
                plain(latestMarket.adjustedClose())
        ));
        input.put("netDebt", plain(netDebt.value()));
        input.put("dilutedShares", plain(dilutedShares.value()));
        input.put("currency", analysis.path("currency").asText("USD"));
        ArrayNode sources = input.putArray("sourceSnapshotIds");
        List.of(
                latestMarket.sourceSnapshotId(),
                revenue.sourceSnapshotId(),
                netDebt.sourceSnapshotId(),
                dilutedShares.sourceSnapshotId()
        ).stream().distinct().forEach(value -> sources.add(value.toString()));
        ArrayNode scenarios = input.putArray("scenarios");
        analysis.path("scenarios").forEach(source -> {
            ObjectNode target = scenarios.addObject();
            target.put("name", source.path("name").asText());
            target.put("revenueGrowth", source.path("revenueGrowth").asText());
            target.put("targetEbitdaMargin", source.path("targetEbitdaMargin").asText());
            target.put("evToEbitdaMultiple", source.path("evToEbitdaMultiple").asText());
            target.put("valuationMethod", source.path("valuationMethod").asText("EV_EBITDA"));
            target.put("valuationMultiple", source.hasNonNull("valuationMultiple")
                    ? source.path("valuationMultiple").asText()
                    : source.path("evToEbitdaMultiple").asText());
            target.put("probability", source.path("probability").asText());
        });
        return input;
    }

    private ResearchInsightsResponse.PriceChart priceChart(
            JsonNode report,
            List<MarketRow> marketRows,
            JsonNode analytics
    ) {
        JsonNode overlays = analytics.path("pricePoints");
        if (overlays.size() != marketRows.size()) {
            throw new IllegalStateException("Analytics price overlay count did not match input");
        }
        List<ResearchInsightsResponse.PricePoint> points = new ArrayList<>(marketRows.size());
        for (int index = 0; index < marketRows.size(); index++) {
            MarketRow row = marketRows.get(index);
            JsonNode overlay = overlays.get(index);
            if (!row.date().toString().equals(overlay.path("date").asText())) {
                throw new IllegalStateException("Analytics price overlay dates did not match input");
            }
            points.add(new ResearchInsightsResponse.PricePoint(
                    row.date(), row.open(), row.high(), row.low(), row.close(),
                    row.adjustedClose(), row.volume(), nullableDecimal(overlay, "ma20"),
                    nullableDecimal(overlay, "ma50")
            ));
        }
        JsonNode summary = analytics.path("technicalSummary");
        List<ResearchInsightsResponse.PriceRangeStats> rangeStats = new ArrayList<>();
        analytics.path("rangeStats").forEach(item -> rangeStats.add(
                new ResearchInsightsResponse.PriceRangeStats(
                        item.path("range").asText(),
                        LocalDate.parse(item.path("periodStart").asText()),
                        LocalDate.parse(item.path("periodEnd").asText()),
                        requiredDecimal(item, "firstPrice"),
                        requiredDecimal(item, "lastPrice"),
                        requiredDecimal(item, "periodReturn"),
                        requiredDecimal(item, "high"),
                        requiredDecimal(item, "low"),
                        requiredDecimal(item, "averageVolume")
                )
        ));
        MarketRow latest = marketRows.getLast();
        return new ResearchInsightsResponse.PriceChart(
                report.path("symbol").asText(),
                report.path("scenarioAnalysis").path("currency").asText("USD"),
                latest.provider(),
                latest.date(),
                latest.retrievedAt(),
                PRICE_METHODOLOGY,
                List.copyOf(points),
                List.copyOf(rangeStats),
                new ResearchInsightsResponse.TechnicalSummary(
                        requiredDecimal(summary, "currentPrice"),
                        nullableDecimal(summary, "priceVsMa20"),
                        nullableDecimal(summary, "priceVsMa50"),
                        technicalSignal(summary.path("signal").asText())
                )
        );
    }

    private ResearchInsightsResponse.ValuationInsights valuation(
            JsonNode report,
            JsonNode analytics
    ) {
        JsonNode matrix = analytics.path("sensitivity");
        List<BigDecimal> growthRates = decimalList(matrix.path("revenueGrowthRates"));
        List<BigDecimal> multiples = decimalList(matrix.path("valuationMultiples"));
        List<ResearchInsightsResponse.SensitivityRow> rows = new ArrayList<>();
        matrix.path("rows").forEach(row -> rows.add(new ResearchInsightsResponse.SensitivityRow(
                requiredDecimal(row, "revenueGrowthRate"),
                decimalList(row.path("impliedPrices")),
                decimalList(row.path("upsideDownside"))
        )));
        String method = analytics.path("valuationMethod").asText();
        String formula = method.equals("EV_REVENUE")
                ? "隐含股价 = max(0, 收入 × (1 + 增长率) × EV/收入倍数 − 净负债) ÷ 稀释股数"
                : "隐含股价 = max(0, 收入 × (1 + 增长率) × EBITDA利润率 × EV/EBITDA倍数 − 净负债) ÷ 稀释股数";
        return new ResearchInsightsResponse.ValuationInsights(
                true,
                null,
                report.path("scenarioAnalysis").path("currency").asText("USD"),
                requiredDecimal(analytics, "currentPrice"),
                requiredDecimal(analytics, "weightedImpliedPrice"),
                requiredDecimal(analytics, "premiumDiscountToWeightedValue"),
                nullableDecimal(analytics, "marketImpliedRevenueGrowth"),
                nullableDecimal(analytics, "marketImpliedGrowthGap"),
                method,
                requiredDecimal(analytics, "baseRevenueGrowth"),
                requiredDecimal(analytics, "baseEbitdaMargin"),
                requiredDecimal(analytics, "baseValuationMultiple"),
                formula,
                List.of(
                        "市场隐含增长使用基准情景的利润率与估值倍数反推，并非市场共识预测。",
                        "敏感性矩阵固定基准利润率，只改变收入增长与估值倍数。",
                        "所有数值由 Analytics insights_v1 从注册输入确定性计算。"
                ),
                new ResearchInsightsResponse.SensitivityMatrix(growthRates, multiples, rows)
        );
    }

    private ResearchInsightsResponse.PeerComparison peers(UUID ownerId, ReportContext target) {
        String targetSymbol = target.report().path("symbol").asText().toUpperCase(Locale.ROOT);
        PeerGroup group = PEER_GROUPS.values().stream()
                .filter(candidate -> candidate.symbols().contains(targetSymbol))
                .findFirst()
                .orElse(null);
        if (group == null) {
            return new ResearchInsightsResponse.PeerComparison(
                    false, null, null, PEER_METHODOLOGY, 0, 0,
                    "暂未为该公司配置可审计的同行组，因此不自动猜测同行。", List.of()
            );
        }

        String placeholders = String.join(",", Collections.nCopies(group.symbols().size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(ownerId);
        arguments.add(target.dataMode().name());
        arguments.add(target.dataMode().name());
        arguments.addAll(group.symbols());
        List<Map<String, Object>> reportRows = jdbc.queryForList("""
                select distinct on (upper(rv.report_json ->> 'symbol'))
                       r.id as research_id, rv.version, rv.data_as_of_date,
                       rv.report_json::text as report_json
                  from report_versions rv
                  join research_jobs r on r.id = rv.research_job_id
                 where r.user_id = ? and r.deleted_at is null
                   and r.data_mode = ? and rv.data_mode = ?
                   and rv.validation_status in %s
                   and upper(rv.report_json ->> 'symbol') in (%s)
                 order by upper(rv.report_json ->> 'symbol'), rv.created_at desc
                """.formatted(PUBLISHED, placeholders), arguments.toArray());

        Map<String, ResearchInsightsResponse.PeerRow> availableRows = new LinkedHashMap<>();
        for (Map<String, Object> row : reportRows) {
            JsonNode report = json(text(row, "report_json"));
            String symbol = report.path("symbol").asText().toUpperCase(Locale.ROOT);
            availableRows.put(symbol, peerRow(
                    symbol,
                    uuid(row, "research_id"),
                    integer(row, "version"),
                    symbol.equals(targetSymbol),
                    date(row, "data_as_of_date"),
                    report
            ));
        }
        availableRows.put(targetSymbol, peerRow(
                targetSymbol,
                target.researchId(),
                target.version(),
                true,
                target.asOfDate(),
                target.report()
        ));
        List<ResearchInsightsResponse.PeerRow> ordered = group.symbols().stream()
                .map(availableRows::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        int available = ordered.size();
        String coverage = available == group.symbols().size()
                ? "已覆盖同行组中的全部公司。"
                : "已覆盖 %d/%d 家；其余公司需先完成一次研究后才会进入比较。"
                        .formatted(available, group.symbols().size());
        return new ResearchInsightsResponse.PeerComparison(
                true,
                group.key(),
                group.label(),
                PEER_METHODOLOGY,
                available,
                group.symbols().size(),
                coverage,
                ordered
        );
    }

    private ResearchInsightsResponse.PeerRow peerRow(
            String symbol,
            UUID researchId,
            int version,
            boolean target,
            LocalDate asOfDate,
            JsonNode report
    ) {
        JsonNode analysis = report.path("scenarioAnalysis");
        JsonNode base = findScenario(analysis, "BASE");
        return new ResearchInsightsResponse.PeerRow(
                symbol,
                researchId,
                version,
                target,
                nullableDecimal(analysis, "currentPrice"),
                nullableDecimal(analysis, "weightedImpliedPrice"),
                base == null ? null : nullableDecimal(base, "upsideDownside"),
                quantMetric(researchId, "revenue_cagr"),
                quantMetric(researchId, "operating_margin"),
                report.path("dataQuality").path("score").asDouble(0),
                asOfDate
        );
    }

    private MetricInput requiredFinancialMetric(UUID researchId, String metricName) {
        List<MetricInput> values = jdbc.query("""
                select metric_value, source_snapshot_id
                  from financial_metrics
                 where research_job_id = ? and metric_name = ?
                 order by period_end_date desc, retrieved_at desc
                 limit 1
                """, (resultSet, rowNumber) -> new MetricInput(
                resultSet.getBigDecimal("metric_value"),
                resultSet.getObject("source_snapshot_id", UUID.class)
        ), researchId, metricName);
        if (values.isEmpty()) {
            throw new IllegalStateException("Published report is missing metric: " + metricName);
        }
        return values.getFirst();
    }

    private BigDecimal quantMetric(UUID researchId, String metricName) {
        List<BigDecimal> values = jdbc.query("""
                select metric_value
                  from quant_results
                 where research_job_id = ? and metric_name = ? and result_status = 'AVAILABLE'
                 order by created_at desc
                 limit 1
                """, (resultSet, rowNumber) -> resultSet.getBigDecimal(1), researchId, metricName);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static JsonNode findScenario(JsonNode analysis, String name) {
        for (JsonNode item : analysis.path("scenarios")) {
            if (name.equals(item.path("name").asText())) {
                return item;
            }
        }
        return null;
    }

    private static List<BigDecimal> decimalList(JsonNode values) {
        List<BigDecimal> result = new ArrayList<>();
        values.forEach(value -> result.add(new BigDecimal(value.asText())));
        return List.copyOf(result);
    }

    private static String technicalSignal(String value) {
        return switch (value) {
            case "ABOVE_BOTH" -> "价格位于两条均线上方";
            case "BELOW_BOTH" -> "价格位于两条均线下方";
            case "MIXED" -> "短中期信号分化";
            default -> "历史长度不足";
        };
    }

    private static BigDecimal nullableDecimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() || value.isNumber() ? new BigDecimal(value.asText()) : null;
    }

    private static BigDecimal requiredDecimal(JsonNode node, String field) {
        BigDecimal value = nullableDecimal(node, field);
        if (value == null) {
            throw new IllegalStateException("Analytics response is missing decimal: " + field);
        }
        return value;
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted report JSON is invalid", exception);
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            throw new IllegalStateException("Required database column is null: " + key);
        }
        return value.toString();
    }

    private static BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(text(row, key));
    }

    private static long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : Long.parseLong(text(row, key));
    }

    private static int integer(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.intValue() : Integer.parseInt(text(row, key));
    }

    private static UUID uuid(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof UUID uuid ? uuid : UUID.fromString(text(row, key));
    }

    private static LocalDate date(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return LocalDate.parse(text(row, key));
    }

    private static Instant instant(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        return Instant.parse(text(row, key));
    }

    private static Map<String, PeerGroup> peerGroups() {
        Map<String, PeerGroup> groups = new LinkedHashMap<>();
        addGroup(groups, "SPACE_AEROSPACE", "商业航天与卫星", "RKLB", "ASTS", "LUNR", "RDW", "SPCE", "PL", "BKSY");
        addGroup(groups, "SEMICONDUCTORS", "半导体与存储", "NVDA", "AMD", "INTC", "AVGO", "QCOM", "MU", "WDC", "STX", "SNDK");
        addGroup(groups, "MEGA_CAP_TECH", "大型科技平台", "AAPL", "MSFT", "GOOGL", "AMZN", "META");
        addGroup(groups, "FINTECH", "金融科技与数字资产", "HOOD", "SOFI", "PYPL", "COIN", "AFRM");
        addGroup(groups, "ELECTRIC_VEHICLES", "电动车与智能出行", "TSLA", "RIVN", "LCID", "NIO");
        addGroup(groups, "CYBERSECURITY", "网络安全", "CRWD", "PANW", "FTNT", "ZS", "OKTA");
        addGroup(groups, "BIOTECH", "生物科技", "MRNA", "BNTX", "REGN", "VRTX");
        return Map.copyOf(groups);
    }

    private static void addGroup(
            Map<String, PeerGroup> groups,
            String key,
            String label,
            String... symbols
    ) {
        groups.put(key, new PeerGroup(key, label, List.of(symbols)));
    }

    private record ReportContext(
            UUID researchId,
            int version,
            DataMode dataMode,
            LocalDate asOfDate,
            JsonNode report
    ) {
    }

    private record MarketRow(
            LocalDate date,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal adjustedClose,
            long volume,
            String provider,
            Instant retrievedAt,
            UUID sourceSnapshotId
    ) {
    }

    private record MetricInput(BigDecimal value, UUID sourceSnapshotId) {
    }

    private record PeerGroup(String key, String label, List<String> symbols) {
    }
}
