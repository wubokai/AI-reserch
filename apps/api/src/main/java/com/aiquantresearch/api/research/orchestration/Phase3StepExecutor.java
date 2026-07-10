package com.aiquantresearch.api.research.orchestration;

import com.aiquantresearch.api.research.analytics.AnalyticsClient;
import com.aiquantresearch.api.research.analytics.AnalyticsRequestFactory;
import com.aiquantresearch.api.research.analytics.AnalyticsServiceException;
import com.aiquantresearch.api.research.domain.StepType;
import com.aiquantresearch.api.research.provider.FilingProvider;
import com.aiquantresearch.api.research.provider.FundamentalDataProvider;
import com.aiquantresearch.api.research.provider.FundamentalDataSnapshot;
import com.aiquantresearch.api.research.provider.MacroDataProvider;
import com.aiquantresearch.api.research.provider.MarketDataProvider;
import com.aiquantresearch.api.research.provider.MarketDataSnapshot;
import com.aiquantresearch.api.research.provider.ProviderDataNotFoundException;
import com.aiquantresearch.api.research.provider.mock.MockFixtureCatalog;
import com.aiquantresearch.api.research.report.DeterministicMockReportGenerator;
import com.aiquantresearch.api.research.report.ReportValidator;
import com.aiquantresearch.api.research.worker.QueueClaim;
import com.aiquantresearch.api.research.worker.StepExecutionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Phase3StepExecutor {

    private static final Set<String> FUNDAMENTAL_METRICS = Set.of(
            "revenue_growth_yoy",
            "revenue_cagr",
            "gross_margin",
            "operating_margin",
            "net_margin",
            "free_cash_flow_margin",
            "eps_growth",
            "debt_to_equity",
            "net_debt",
            "current_ratio",
            "interest_coverage",
            "return_on_equity",
            "return_on_assets",
            "return_on_invested_capital",
            "share_dilution",
            "capex_trend_slope",
            "market_capitalization",
            "price_to_earnings",
            "forward_price_to_earnings",
            "price_to_sales",
            "price_to_book",
            "enterprise_value",
            "enterprise_value_to_revenue",
            "enterprise_value_to_ebitda",
            "free_cash_flow_yield"
    );

    private static final Set<String> REQUIRED_REPORT_METRICS = Set.of(
            "total_return",
            "cagr",
            "annualized_volatility",
            "max_drawdown",
            "sharpe_ratio",
            "scenario_bull_implied_price",
            "scenario_base_implied_price",
            "scenario_bear_implied_price",
            "weighted_scenario_value"
    );

    private final Phase3ArtifactStore artifactStore;
    private final MarketDataProvider marketDataProvider;
    private final FundamentalDataProvider fundamentalDataProvider;
    private final FilingProvider filingProvider;
    private final MacroDataProvider macroDataProvider;
    private final MockFixtureCatalog fixtureCatalog;
    private final AnalyticsRequestFactory analyticsRequestFactory;
    private final AnalyticsClient analyticsClient;
    private final DeterministicMockReportGenerator reportGenerator;
    private final ReportValidator reportValidator;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public Phase3StepExecutor(
            Phase3ArtifactStore artifactStore,
            MarketDataProvider marketDataProvider,
            FundamentalDataProvider fundamentalDataProvider,
            FilingProvider filingProvider,
            MacroDataProvider macroDataProvider,
            MockFixtureCatalog fixtureCatalog,
            AnalyticsRequestFactory analyticsRequestFactory,
            AnalyticsClient analyticsClient,
            DeterministicMockReportGenerator reportGenerator,
            ReportValidator reportValidator,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.artifactStore = artifactStore;
        this.marketDataProvider = marketDataProvider;
        this.fundamentalDataProvider = fundamentalDataProvider;
        this.filingProvider = filingProvider;
        this.macroDataProvider = macroDataProvider;
        this.fixtureCatalog = fixtureCatalog;
        this.analyticsRequestFactory = analyticsRequestFactory;
        this.analyticsClient = analyticsClient;
        this.reportGenerator = reportGenerator;
        this.reportValidator = reportValidator;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public StepExecutionResult execute(QueueClaim claim) {
        ResearchExecutionContext context = artifactStore.context(claim.researchJobId());
        try {
            return switch (claim.stepType()) {
                case RESOLVE_SECURITY -> resolveSecurity(context);
                case FETCH_MARKET_DATA -> fetchMarketData(context);
                case FETCH_FUNDAMENTALS -> StepExecutionResult.complete(
                        objectMapper.valueToTree(fundamentalDataProvider.fetch(context.symbol()))
                );
                case FETCH_FILINGS -> StepExecutionResult.complete(
                        objectMapper.valueToTree(filingProvider.fetch(context.symbol()))
                );
                case FETCH_MACRO_DATA -> StepExecutionResult.complete(
                        objectMapper.valueToTree(macroDataProvider.fetch())
                );
                case VALIDATE_DATA -> validateData(context);
                case RUN_QUANT_ANALYSIS -> runQuantAnalysis(context);
                case ANALYZE_FUNDAMENTALS -> analyzeFundamentals(context);
                case BUILD_EVIDENCE -> buildEvidence(context);
                case GENERATE_REPORT -> generateReport(context);
                case VALIDATE_REPORT -> validateReport(context);
            };
        } catch (ProviderDataNotFoundException exception) {
            throw new StepExecutionException(
                    "MOCK_FIXTURE_NOT_FOUND",
                    "The requested security is outside the Phase 3 Mock coverage",
                    false,
                    exception
            );
        }
    }

    private StepExecutionResult resolveSecurity(ResearchExecutionContext context) {
        String symbol = context.request().path("symbol").asText(context.symbol());
        var fixture = fixtureCatalog.security(symbol);
        UUID securityId = jdbcTemplate.queryForObject(
                "select id from securities where symbol = ? and active and is_demo_data",
                UUID.class,
                fixture.symbol()
        );
        if (securityId == null) {
            throw new StepExecutionException(
                    "SECURITY_NOT_FOUND",
                    "The requested security is not in the local Mock security master",
                    false
            );
        }
        ObjectNode output = objectMapper.createObjectNode();
        output.put("fixtureVersion", fixtureCatalog.manifest().fixtureVersion());
        output.put("asOfDate", fixtureCatalog.manifest().asOfDate().toString());
        output.put("securityId", securityId.toString());
        output.put("symbol", fixture.symbol());
        output.put("companyName", fixture.companyName());
        output.put("exchange", fixture.exchange());
        output.put("securityType", fixture.securityType());
        output.put("currency", fixture.currency());
        output.put("watermark", fixtureCatalog.manifest().watermark());
        return StepExecutionResult.complete(output);
    }

    private StepExecutionResult fetchMarketData(ResearchExecutionContext context) {
        String benchmark = context.request().path("benchmark").asText("SPY");
        ObjectNode output = objectMapper.createObjectNode();
        output.set("target", objectMapper.valueToTree(
                marketDataProvider.fetchFiveYearDaily(context.symbol())
        ));
        output.set("benchmark", objectMapper.valueToTree(
                marketDataProvider.fetchFiveYearDaily(benchmark)
        ));
        return StepExecutionResult.complete(output);
    }

    private StepExecutionResult validateData(ResearchExecutionContext context) {
        StoredSource market = requireSource(context.researchId(), "MARKET_DATA");
        StoredSource benchmark = requireSource(context.researchId(), "BENCHMARK_DATA");
        MarketDataSnapshot target = tree(market.payload(), MarketDataSnapshot.class);
        MarketDataSnapshot reference = tree(benchmark.payload(), MarketDataSnapshot.class);
        if (target.prices().size() < 1_290 || reference.prices().size() < 1_290) {
            throw new StepExecutionException(
                    "MARKET_DATA_INCOMPLETE",
                    "The fixed five-year market series is incomplete",
                    false
            );
        }
        if (!target.periodStart().equals(reference.periodStart())
                || !target.periodEnd().equals(reference.periodEnd())) {
            throw new StepExecutionException(
                    "BENCHMARK_PERIOD_MISMATCH",
                    "The security and benchmark periods do not match",
                    false
            );
        }

        boolean fundamentalRequested = context.request()
                .path("includeFundamentalAnalysis").asBoolean(true);
        boolean macroRequested = context.request().path("includeMacroAnalysis").asBoolean(true);
        boolean partial = (fundamentalRequested
                && artifactStore.source(context.researchId(), "FUNDAMENTALS").isEmpty())
                || (macroRequested && artifactStore.source(context.researchId(), "MACRO").isEmpty())
                || artifactStore.source(context.researchId(), "FILING").isEmpty();
        List<String> warnings = partial
                ? List.of("One or more optional Mock modules are unavailable")
                : List.of();
        ObjectNode output = objectMapper.createObjectNode();
        output.put("status", partial ? "VALID_WITH_WARNINGS" : "VALID");
        output.put("marketSampleSize", target.prices().size());
        output.put("benchmarkSampleSize", reference.prices().size());
        output.put("periodStart", target.periodStart().toString());
        output.put("periodEnd", target.periodEnd().toString());
        output.put("watermark", MockFixtureCatalog.EXPECTED_WATERMARK);
        return new StepExecutionResult(output, partial, warnings);
    }

    private StepExecutionResult runQuantAnalysis(ResearchExecutionContext context) {
        StoredSource targetSource = requireSource(context.researchId(), "MARKET_DATA");
        StoredSource benchmarkSource = requireSource(context.researchId(), "BENCHMARK_DATA");
        MarketDataSnapshot target = tree(targetSource.payload(), MarketDataSnapshot.class);
        MarketDataSnapshot benchmark = tree(benchmarkSource.payload(), MarketDataSnapshot.class);
        StoredSource fundamentalSource = artifactStore.source(
                context.researchId(),
                "FUNDAMENTALS"
        ).orElse(null);
        FundamentalDataSnapshot fundamentals = fundamentalSource == null
                ? new FundamentalDataSnapshot(
                        fixtureCatalog.manifest().fixtureVersion(),
                        context.symbol(),
                        fixtureCatalog.manifest().asOfDate(),
                        List.of(),
                        List.of(),
                        fixtureCatalog.manifest().watermark()
                )
                : tree(fundamentalSource.payload(), FundamentalDataSnapshot.class);
        String fundamentalId = fundamentalSource == null
                ? targetSource.id().toString()
                : fundamentalSource.id().toString();
        JsonNode request = analyticsRequestFactory.create(
                context.securityType(),
                target,
                targetSource.id().toString(),
                benchmark,
                benchmarkSource.id().toString(),
                fundamentals,
                fundamentalId
        );
        try {
            JsonNode response = analyticsClient.runFullAnalysis(request);
            boolean partial = hasUnavailableRequiredReportMetric(response);
            List<String> warnings = new java.util.ArrayList<>();
            response.path("warnings").forEach(item -> {
                String metricName = item.path("metricName").asText();
                if (metricName.isBlank() || REQUIRED_REPORT_METRICS.contains(metricName)) {
                    warnings.add(item.path("code").asText());
                }
            });
            return new StepExecutionResult(response, partial, warnings);
        } catch (AnalyticsServiceException exception) {
            throw new StepExecutionException(
                    "ANALYTICS_UNAVAILABLE",
                    exception.getMessage(),
                    exception.isRetryable(),
                    exception
            );
        }
    }

    static boolean hasUnavailableRequiredReportMetric(JsonNode response) {
        java.util.Map<String, String> statuses = new java.util.HashMap<>();
        response.path("metrics").forEach(metric -> statuses.put(
                metric.path("name").asText(),
                metric.path("status").asText()
        ));
        return REQUIRED_REPORT_METRICS.stream()
                .anyMatch(name -> !"AVAILABLE".equals(statuses.get(name)));
    }

    private StepExecutionResult analyzeFundamentals(ResearchExecutionContext context) {
        List<StoredQuantResult> metrics = artifactStore.quantResults(context.researchId()).stream()
                .filter(result -> FUNDAMENTAL_METRICS.contains(result.metricName()))
                .toList();
        ObjectNode output = objectMapper.createObjectNode();
        output.put("availableMetricCount", metrics.stream()
                .filter(result -> "AVAILABLE".equals(result.status()))
                .count());
        output.set("metrics", objectMapper.valueToTree(metrics.stream()
                .map(StoredQuantResult::publicId)
                .toList()));
        boolean partial = metrics.stream().noneMatch(result -> "AVAILABLE".equals(result.status()));
        return new StepExecutionResult(
                output,
                partial,
                partial ? List.of("FUNDAMENTAL_METRICS_UNAVAILABLE") : List.of()
        );
    }

    private StepExecutionResult buildEvidence(ResearchExecutionContext context) {
        if (artifactStore.quantResults(context.researchId()).stream()
                .noneMatch(result -> "AVAILABLE".equals(result.status()))) {
            throw new StepExecutionException(
                    "QUANT_EVIDENCE_UNAVAILABLE",
                    "No available deterministic quant results can be registered as Evidence",
                    false
            );
        }
        ObjectNode output = objectMapper.createObjectNode();
        output.put("sourceCount", artifactStore.sources(context.researchId()).size());
        output.put("quantResultCount", artifactStore.quantResults(context.researchId()).size());
        output.put("registryVersion", "evidence_registry_v1");
        return StepExecutionResult.complete(output);
    }

    private StepExecutionResult generateReport(ResearchExecutionContext context) {
        List<StoredEvidence> evidence = artifactStore.evidence(context.researchId());
        if (evidence.isEmpty()) {
            throw new StepExecutionException(
                    "EVIDENCE_REGISTRY_EMPTY",
                    "A report cannot be generated without registered Evidence",
                    false
            );
        }
        try {
            JsonNode report = reportGenerator.generate(
                    context,
                    artifactStore.sources(context.researchId()),
                    artifactStore.quantResults(context.researchId()),
                    evidence,
                    artifactStore.nextReportVersion(context.researchId())
            );
            return StepExecutionResult.complete(report);
        } catch (IllegalArgumentException exception) {
            throw new StepExecutionException(
                    "REPORT_GENERATION_INPUT_INVALID",
                    "The deterministic report inputs are incomplete",
                    false,
                    exception
            );
        }
    }

    private StepExecutionResult validateReport(ResearchExecutionContext context) {
        JsonNode candidate = artifactStore.generatedReport(context.researchId()).orElseThrow(
                () -> new StepExecutionException(
                        "REPORT_CANDIDATE_MISSING",
                        "The generated report candidate is unavailable",
                        false
                )
        );
        var validation = reportValidator.validate(
                candidate,
                artifactStore.quantResults(context.researchId()),
                artifactStore.evidence(context.researchId()),
                context
        );
        if (!validation.valid()) {
            throw new StepExecutionException(
                    "REPORT_VALIDATION_FAILED",
                    "The report candidate failed Evidence and numeric validation",
                    false
            );
        }
        return new StepExecutionResult(
                validation.report(),
                validation.partial(),
                validation.warnings()
        );
    }

    private StoredSource requireSource(UUID researchId, String purpose) {
        return artifactStore.source(researchId, purpose).orElseThrow(() -> new StepExecutionException(
                "SOURCE_ARTIFACT_MISSING",
                "A required " + purpose + " source artifact is unavailable",
                false
        ));
    }

    private <T> T tree(JsonNode node, Class<T> type) {
        try {
            return objectMapper.treeToValue(node, type);
        } catch (JsonProcessingException exception) {
            throw new StepExecutionException(
                    "SOURCE_ARTIFACT_INVALID",
                    "A stored source artifact does not match its deterministic contract",
                    false,
                    exception
            );
        }
    }
}
