package com.aiquantresearch.api.research.orchestration;

import com.aiquantresearch.api.research.analytics.AnalyticsClient;
import com.aiquantresearch.api.research.analytics.AnalyticsRequestFactory;
import com.aiquantresearch.api.research.analytics.AnalyticsServiceException;
import com.aiquantresearch.api.research.domain.StepType;
import com.aiquantresearch.api.research.domain.ReportDepth;
import com.aiquantresearch.api.research.domain.ResearchPeriod;
import com.aiquantresearch.api.research.filing.FilingTextProcessor;
import com.aiquantresearch.api.research.llm.OpenAiResponseException;
import com.aiquantresearch.api.research.llm.ResearchLanguageModelRequest;
import com.aiquantresearch.api.research.llm.ResearchLanguageModelRouter;
import com.aiquantresearch.api.research.provider.FilingProvider;
import com.aiquantresearch.api.research.provider.FilingSnapshot;
import com.aiquantresearch.api.research.provider.FundamentalDataProvider;
import com.aiquantresearch.api.research.provider.FundamentalDataSnapshot;
import com.aiquantresearch.api.research.provider.MacroDataProvider;
import com.aiquantresearch.api.research.provider.MarketDataProvider;
import com.aiquantresearch.api.research.provider.MarketDataSnapshot;
import com.aiquantresearch.api.research.provider.ProviderAccessException;
import com.aiquantresearch.api.research.provider.ProviderDataNotFoundException;
import com.aiquantresearch.api.research.provider.mock.MockFixtureCatalog;
import com.aiquantresearch.api.research.report.ReportValidator;
import com.aiquantresearch.api.research.report.ReportRepairService;
import com.aiquantresearch.api.research.worker.QueueClaim;
import com.aiquantresearch.api.research.worker.StepExecutionException;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashSet;
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
    private final ResearchLanguageModelRouter languageModel;
    private final ReportValidator reportValidator;
    private final ReportRepairService reportRepairService;
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
            ResearchLanguageModelRouter languageModel,
            ReportValidator reportValidator,
            ReportRepairService reportRepairService,
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
        this.languageModel = languageModel;
        this.reportValidator = reportValidator;
        this.reportRepairService = reportRepairService;
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
                case FETCH_FILINGS -> fetchFilings(context);
                case FETCH_MACRO_DATA -> StepExecutionResult.complete(
                        objectMapper.valueToTree(macroDataProvider.fetch())
                );
                case VALIDATE_DATA -> validateData(context);
                case RUN_QUANT_ANALYSIS -> runQuantAnalysis(context);
                case ANALYZE_FUNDAMENTALS -> analyzeFundamentals(context);
                case BUILD_EVIDENCE -> buildEvidence(context);
                case GENERATE_REPORT -> generateReport(claim, context);
                case VALIDATE_REPORT -> validateReport(context);
            };
        } catch (ProviderAccessException exception) {
            throw new StepExecutionException(
                    exception.code(),
                    exception.getMessage(),
                    exception.retryable(),
                    exception
            );
        } catch (ProviderDataNotFoundException exception) {
            throw new StepExecutionException(
                    "PROVIDER_DATA_NOT_FOUND",
                    "The requested security has no data from the configured provider",
                    false,
                    exception
            );
        }
    }

    private StepExecutionResult resolveSecurity(ResearchExecutionContext context) {
        String symbol = context.request().path("symbol").asText(context.symbol());
        if (context.dataMode() == DataMode.REAL) {
            return resolveRealSecurity(symbol);
        }
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

    StepExecutionResult resolveRealSecurity(String symbol) {
        var matches = jdbcTemplate.query("""
                select id, symbol, company_name, exchange, security_type, currency,
                       updated_at, statement_timestamp() as retrieved_at
                  from securities
                 where upper(symbol) = upper(?) and active and not is_demo_data
                 order by id
                """, (row, ignored) -> {
            ObjectNode output = objectMapper.createObjectNode();
            output.put("provider", "LOCAL_SECURITY_MASTER");
            output.put("schemaVersion", "security_master_v1");
            output.put("securityId", row.getObject("id", UUID.class).toString());
            output.put("symbol", row.getString("symbol"));
            output.put("companyName", row.getString("company_name"));
            output.put("exchange", row.getString("exchange"));
            output.put("securityType", row.getString("security_type"));
            output.put("currency", row.getString("currency"));
            output.put("asOfDate", row.getTimestamp("updated_at").toInstant()
                    .atZone(ZoneOffset.UTC).toLocalDate().toString());
            output.put("retrievedAt", row.getObject("retrieved_at", Timestamp.class)
                    .toInstant().toString());
            output.put("freshnessStatus", "FRESH");
            output.put("demoData", false);
            return output;
        }, symbol);
        if (matches.size() != 1) {
            throw new StepExecutionException(
                    matches.isEmpty() ? "REAL_SECURITY_MASTER_MISSING" : "SECURITY_AMBIGUOUS",
                    matches.isEmpty()
                            ? "The requested security is not registered in the real security master"
                            : "The requested symbol resolves to multiple active real securities",
                    false
            );
        }
        return StepExecutionResult.complete(matches.getFirst());
    }

    private StepExecutionResult fetchMarketData(ResearchExecutionContext context) {
        String benchmark = context.request().path("benchmark").asText("SPY");
        MarketDataSnapshot target = marketDataProvider.fetchFiveYearDaily(context.symbol());
        MarketDataSnapshot reference = marketDataProvider.fetchFiveYearDaily(benchmark);
        LocalDate availableEnd = target.periodEnd().isBefore(reference.periodEnd())
                ? target.periodEnd()
                : reference.periodEnd();
        LocalDate end = requestedEnd(context, availableEnd);
        LocalDate requestedStart = requestedStart(context, end);
        LocalDate start = requestedStart;
        if (target.periodStart().isAfter(start)) {
            start = target.periodStart();
        }
        if (reference.periodStart().isAfter(start)) {
            start = reference.periodStart();
        }
        MarketDataSnapshot selectedTarget = target.within(start, end);
        MarketDataSnapshot selectedReference = reference.within(start, end);
        boolean shorterThanRequested = MarketHistoryPolicy.isShorterThanRequested(
                requestedStart,
                start
        );
        ObjectNode targetPayload = objectMapper.valueToTree(selectedTarget);
        ObjectNode referencePayload = objectMapper.valueToTree(selectedReference);
        annotateCoverage(targetPayload, requestedStart, end, shorterThanRequested);
        annotateCoverage(referencePayload, requestedStart, end, shorterThanRequested);
        ObjectNode output = objectMapper.createObjectNode();
        output.set("target", targetPayload);
        output.set("benchmark", referencePayload);
        return new StepExecutionResult(
                output,
                shorterThanRequested,
                shorterThanRequested
                        ? List.of("MARKET_HISTORY_SHORTER_THAN_REQUESTED")
                        : List.of()
        );
    }

    private StepExecutionResult fetchFilings(ResearchExecutionContext context) {
        FilingSnapshot snapshot = filingProvider.fetch(context.symbol()).limitedTo(
                reportDepth(context).maxFilings()
        );
        ObjectNode payload = objectMapper.valueToTree(snapshot);
        boolean bounded = false;
        JsonNode filingNodes = payload.path("filings");
        for (int index = 0; index < snapshot.filings().size(); index++) {
            var document = snapshot.filings().get(index);
            boolean documentBounded = FilingTextProcessor.requiresBoundedProcessing(
                    document.contentHtml()
            );
            bounded = bounded || documentBounded;
            if (filingNodes.isArray() && filingNodes.get(index) instanceof ObjectNode filingNode) {
                filingNode.put(
                        "contentProcessingStatus",
                        documentBounded ? "BOUNDED" : "COMPLETE"
                );
                if (documentBounded) {
                    filingNode.put(
                            "processedCharacterLimit",
                            FilingTextProcessor.maximumSourceCharacters()
                    );
                }
            }
        }
        return new StepExecutionResult(
                payload,
                bounded,
                bounded ? List.of("FILING_CONTENT_TRUNCATED") : List.of()
        );
    }

    private static void annotateCoverage(
            ObjectNode payload,
            LocalDate requestedStart,
            LocalDate requestedEnd,
            boolean shorterThanRequested
    ) {
        payload.put("requestedPeriodStart", requestedStart.toString());
        payload.put("requestedPeriodEnd", requestedEnd.toString());
        payload.put(
                "coverageStatus",
                shorterThanRequested ? "SHORTER_THAN_REQUESTED" : "COMPLETE"
        );
    }

    private StepExecutionResult validateData(ResearchExecutionContext context) {
        StoredSource market = requireSource(context.researchId(), "MARKET_DATA");
        StoredSource benchmark = requireSource(context.researchId(), "BENCHMARK_DATA");
        MarketDataSnapshot target = tree(market.payload(), MarketDataSnapshot.class);
        MarketDataSnapshot reference = tree(benchmark.payload(), MarketDataSnapshot.class);
        if (!MarketHistoryPolicy.hasMinimumObservations(
                target.prices().size(),
                reference.prices().size()
        )) {
            throw new StepExecutionException(
                    "MARKET_DATA_INCOMPLETE",
                    "The requested market series is incomplete for deterministic analysis",
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
                ? List.of("One or more optional research modules are unavailable")
                : List.of();
        ObjectNode output = objectMapper.createObjectNode();
        output.put("status", partial ? "VALID_WITH_WARNINGS" : "VALID");
        output.put("marketSampleSize", target.prices().size());
        output.put("benchmarkSampleSize", reference.prices().size());
        output.put("periodStart", target.periodStart().toString());
        output.put("periodEnd", target.periodEnd().toString());
        if (context.dataMode() == DataMode.MOCK) {
            output.put("watermark", MockFixtureCatalog.EXPECTED_WATERMARK);
        }
        return new StepExecutionResult(output, partial, warnings);
    }

    private static ReportDepth reportDepth(ResearchExecutionContext context) {
        return ReportDepth.fromRequestValue(
                context.request().path("reportDepth").asText(ReportDepth.STANDARD.name())
        );
    }

    private static LocalDate requestedEnd(
            ResearchExecutionContext context,
            LocalDate availableEnd
    ) {
        String explicit = context.request().path("endDate").asText();
        return explicit.isBlank() ? availableEnd : LocalDate.parse(explicit);
    }

    private static LocalDate requestedStart(
            ResearchExecutionContext context,
            LocalDate end
    ) {
        String explicit = context.request().path("startDate").asText();
        if (!explicit.isBlank()) {
            return LocalDate.parse(explicit);
        }
        ResearchPeriod period = ResearchPeriod.fromValue(
                context.request().path("period").asText("5y")
        );
        return switch (period) {
            case ONE_YEAR -> end.minusYears(1).plusDays(1);
            case THREE_YEARS -> end.minusYears(3).plusDays(1);
            case FIVE_YEARS -> end.minusYears(5).plusDays(1);
            case TEN_YEARS, MAX -> throw new StepExecutionException(
                    "RESEARCH_PERIOD_UNSUPPORTED",
                    "The requested research period is outside the supported boundary",
                    false
            );
        };
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
                ? emptyFundamentals(context, target)
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

    private FundamentalDataSnapshot emptyFundamentals(
            ResearchExecutionContext context,
            MarketDataSnapshot target
    ) {
        if (context.dataMode() == DataMode.MOCK) {
            return new FundamentalDataSnapshot(
                    fixtureCatalog.manifest().fixtureVersion(),
                    context.symbol(),
                    fixtureCatalog.manifest().asOfDate(),
                    List.of(),
                    List.of(),
                    fixtureCatalog.manifest().watermark()
            );
        }
        return new FundamentalDataSnapshot(
                null,
                null,
                null,
                context.symbol(),
                target.periodEnd(),
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of("FUNDAMENTALS_NOT_AVAILABLE"),
                null,
                false,
                "UNKNOWN",
                null
        );
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

    private StepExecutionResult generateReport(
            QueueClaim claim,
            ResearchExecutionContext context
    ) {
        List<StoredEvidence> evidence = artifactStore.evidence(context.researchId());
        if (evidence.isEmpty()) {
            throw new StepExecutionException(
                    "EVIDENCE_REGISTRY_EMPTY",
                    "A report cannot be generated without registered Evidence",
                    false
            );
        }
        try {
            var result = languageModel.generateReport(new ResearchLanguageModelRequest(
                    claim.attemptId(),
                    context,
                    artifactStore.sources(context.researchId()),
                    artifactStore.quantResults(context.researchId()),
                    evidence,
                    artifactStore.nextReportVersion(context.researchId())
            ));
            return new StepExecutionResult(
                    result.report(),
                    result.partial(),
                    result.warnings(),
                    result.audit()
            );
        } catch (OpenAiResponseException exception) {
            throw new StepExecutionException(
                    exception.code(),
                    exception.getMessage(),
                    exception.retryable(),
                    exception
            );
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
            var repair = reportRepairService.repairOnce(
                    candidate,
                    validation.warnings(),
                    artifactStore.evidence(context.researchId()),
                    artifactStore.quantResults(context.researchId()),
                    context
            );
            var repairedValidation = reportValidator.validate(
                    repair.report(),
                    artifactStore.quantResults(context.researchId()),
                    artifactStore.evidence(context.researchId()),
                    context
            );
            if (!repairedValidation.valid()) {
                throw new StepExecutionException(
                        "REPORT_VALIDATION_FAILED_AFTER_REPAIR",
                        "The report candidate failed its single constrained repair: "
                                + String.join(", ", repairedValidation.warnings()),
                        false
                );
            }
            List<String> repairWarnings = new java.util.ArrayList<>(
                    upstreamWarnings(context.researchId())
            );
            repairWarnings.add("REPORT_REPAIRED_ONCE");
            repair.prunedClaimIds().stream()
                    .map(id -> "REPORT_UNSAFE_CLAIM_PRUNED:" + id)
                    .forEach(repairWarnings::add);
            return new StepExecutionResult(
                    repairedValidation.report(),
                    true,
                    repairWarnings
            );
        }
        List<String> warnings = mergeWarnings(
                upstreamWarnings(context.researchId()),
                validation.warnings()
        );
        return new StepExecutionResult(
                validation.report(),
                validation.partial() || !warnings.isEmpty(),
                warnings
        );
    }

    List<String> upstreamWarnings(UUID researchId) {
        List<String> warnings = jdbcTemplate.query("""
                select warning.value
                  from research_steps step
                  join step_attempts attempt
                    on attempt.research_step_id = step.id
                  cross join lateral jsonb_array_elements_text(
                    coalesce(attempt.output_manifest_json -> 'warnings', '[]'::jsonb)
                  ) with ordinality as warning(value, position)
                 where step.research_job_id = ?
                   and attempt.status = 'SUCCEEDED'
                   and attempt.output_hash = step.successful_output_hash
                 order by step.sequence_no, attempt.attempt_number, warning.position
                """, (row, ignored) -> row.getString("value"), researchId);
        return mergeWarnings(warnings, List.of());
    }

    private static List<String> mergeWarnings(List<String> first, List<String> second) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.addAll(first);
        merged.addAll(second);
        return List.copyOf(merged);
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
