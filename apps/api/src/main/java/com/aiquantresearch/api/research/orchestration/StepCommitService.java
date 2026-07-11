package com.aiquantresearch.api.research.orchestration;

import com.aiquantresearch.api.research.application.CanonicalHashService;
import com.aiquantresearch.api.research.domain.StepType;
import com.aiquantresearch.api.research.filing.FilingRegistry;
import com.aiquantresearch.api.research.llm.LlmBudgetService;
import com.aiquantresearch.api.research.worker.DurableQueueClient;
import com.aiquantresearch.api.research.worker.QueueClaim;
import com.aiquantresearch.api.research.worker.StepExecutionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class StepCommitService {

    private final Phase3ArtifactStore artifactStore;
    private final EvidenceBuilder evidenceBuilder;
    private final FilingRegistry filingRegistry;
    private final LlmBudgetService llmBudgetService;
    private final DurableQueueClient queueClient;
    private final ReportPublicationService publicationService;
    private final CanonicalHashService hashService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public StepCommitService(
            Phase3ArtifactStore artifactStore,
            EvidenceBuilder evidenceBuilder,
            FilingRegistry filingRegistry,
            LlmBudgetService llmBudgetService,
            DurableQueueClient queueClient,
            ReportPublicationService publicationService,
            CanonicalHashService hashService,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate
    ) {
        this.artifactStore = artifactStore;
        this.evidenceBuilder = evidenceBuilder;
        this.filingRegistry = filingRegistry;
        this.llmBudgetService = llmBudgetService;
        this.queueClient = queueClient;
        this.publicationService = publicationService;
        this.hashService = hashService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void commit(QueueClaim claim, StepExecutionResult result) {
        ObjectNode hashEnvelope = objectMapper.createObjectNode();
        hashEnvelope.set("payload", result.payload());
        hashEnvelope.put("partial", result.partial());
        hashEnvelope.set("warnings", objectMapper.valueToTree(result.warnings()));
        String outputHash = hashService.hash(hashEnvelope);

        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("schemaVersion", "step_output_manifest_v1");
        manifest.put("stepType", claim.stepType().name());
        manifest.put("outputHash", outputHash);
        manifest.put("partial", result.partial());
        manifest.set("warnings", objectMapper.valueToTree(result.warnings()));
        ArrayNode artifactIds = manifest.putArray("artifactIds");

        persistArtifacts(claim, result, outputHash, manifest, artifactIds);

        if (claim.stepType() == StepType.VALIDATE_REPORT) {
            publicationService.publish(claim, result, outputHash, manifest);
            return;
        }

        var completion = queueClient.completeAndAdvance(
                claim.attemptId(),
                claim.leaseToken(),
                outputHash,
                manifest
        );
        if (!completion.committed()) {
            throw new StepExecutionException(
                    "QUEUE_COMMIT_REJECTED",
                    "The durable queue rejected a stale or conflicting step result",
                    false
            );
        }
    }

    private void persistArtifacts(
            QueueClaim claim,
            StepExecutionResult result,
            String outputHash,
            ObjectNode manifest,
            ArrayNode artifactIds
    ) {
        switch (claim.stepType()) {
            case RESOLVE_SECURITY -> persistSecurityResolution(claim, result.payload(), artifactIds);
            case FETCH_MARKET_DATA -> persistMarketData(claim, result.payload(), artifactIds);
            case FETCH_FUNDAMENTALS -> {
                StoredSource source = persistSingleSource(
                        claim,
                        result.payload(),
                        "MOCK_FUNDAMENTALS_V1",
                        "mock_fundamentals_v1",
                        "FUNDAMENTALS",
                        result.payload().path("symbol").asText(),
                        date(result.payload(), "asOfDate"),
                        false,
                        artifactIds
                );
                persistFinancialMetrics(claim, source, result.payload());
            }
            case FETCH_FILINGS -> {
                StoredSource source = persistSingleSource(
                        claim,
                        result.payload(),
                        "MOCK_FILINGS_V1",
                        "mock_filings_v1",
                        "FILING",
                        result.payload().path("symbol").asText(),
                        date(result.payload(), "asOfDate"),
                        true,
                        artifactIds
                );
                filingRegistry.register(source, result.payload());
            }
            case FETCH_MACRO_DATA -> {
                StoredSource source = persistSingleSource(
                        claim,
                        result.payload(),
                        "MOCK_MACRO_V1",
                        "mock_macro_v1",
                        "MACRO",
                        "US_MACRO",
                        date(result.payload(), "asOfDate"),
                        true,
                        artifactIds
                );
                persistMacroSeries(claim, source, result.payload());
            }
            case RUN_QUANT_ANALYSIS -> artifactStore.persistQuantMetrics(claim, result.payload())
                    .forEach(value -> artifactIds.add(value.id().toString()));
            case BUILD_EVIDENCE -> {
                var drafts = evidenceBuilder.build(
                        artifactStore.sources(claim.researchJobId()),
                        artifactStore.quantResults(claim.researchJobId())
                );
                artifactStore.persistEvidence(claim, drafts)
                        .forEach(value -> artifactIds.add(value.id().toString()));
            }
            case GENERATE_REPORT -> {
                if (result.llmCallAudit() == null) {
                    throw new StepExecutionException(
                            "LLM_CALL_AUDIT_MISSING",
                            "A generated report is missing its LLM call audit",
                            false
                    );
                }
                UUID callId = artifactStore.persistLlmCall(claim, result.llmCallAudit());
                llmBudgetService.settle(
                        result.llmCallAudit().budgetReservationId(),
                        result.llmCallAudit().estimatedCostUsd(),
                        result.llmCallAudit().networkCallCount()
                );
                artifactIds.add(callId.toString());
                manifest.put("llmCallId", callId.toString());
                manifest.set("report", result.payload());
            }
            case VALIDATE_DATA, ANALYZE_FUNDAMENTALS, VALIDATE_REPORT -> {
                // These steps validate or summarize artifacts already registered above.
            }
        }
    }

    private void persistSecurityResolution(
            QueueClaim claim,
            JsonNode payload,
            ArrayNode artifactIds
    ) {
        UUID securityId = UUID.fromString(payload.path("securityId").asText());
        int updated = jdbcTemplate.update("""
                update research_jobs
                   set security_id = ?, row_version = row_version + 1, updated_by = user_id
                 where id = ? and (security_id is null or security_id = ?)
                """, securityId, claim.researchJobId(), securityId);
        if (updated != 1) {
            throw new StepExecutionException(
                    "SECURITY_RESOLUTION_CONFLICT",
                    "The resolved security conflicts with the durable research job",
                    false
            );
        }
        persistSingleSource(
                claim,
                payload,
                "MOCK_SECURITY_MASTER_V1",
                "mock_security_profile_v1",
                "SECURITY_PROFILE",
                payload.path("symbol").asText(),
                date(payload, "asOfDate"),
                true,
                artifactIds
        );
    }

    private void persistMarketData(
            QueueClaim claim,
            JsonNode payload,
            ArrayNode artifactIds
    ) {
        JsonNode target = payload.path("target");
        JsonNode benchmark = payload.path("benchmark");
        StoredSource targetSource = persistSingleSource(
                claim,
                target,
                "MOCK_MARKET_V1",
                "mock_market_daily_v1",
                "MARKET_DATA",
                target.path("symbol").asText(),
                date(target, "periodEnd"),
                false,
                artifactIds
        );
        StoredSource benchmarkSource = persistSingleSource(
                claim,
                benchmark,
                "MOCK_MARKET_V1",
                "mock_market_daily_v1",
                "BENCHMARK_DATA",
                benchmark.path("symbol").asText(),
                date(benchmark, "periodEnd"),
                false,
                artifactIds
        );
        persistMarketPriceBars(claim, targetSource, target);
        persistMarketPriceBars(claim, benchmarkSource, benchmark);
    }

    private void persistMarketPriceBars(
            QueueClaim claim,
            StoredSource source,
            JsonNode payload
    ) {
        String symbol = payload.path("symbol").asText();
        List<Object[]> rows = new java.util.ArrayList<>();
        payload.path("prices").forEach(bar -> rows.add(new Object[]{
                UUID.randomUUID(),
                claim.researchJobId(),
                symbol,
                claim.researchJobId(),
                symbol,
                date(bar, "date"),
                decimal(bar, "open"),
                decimal(bar, "high"),
                decimal(bar, "low"),
                decimal(bar, "close"),
                decimal(bar, "adjustedClose"),
                bar.path("volume").longValue(),
                source.id()
        }));
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                insert into market_price_bars (
                    id, research_job_id, source_snapshot_id, security_id,
                    symbol, interval, observation_date, open, high, low,
                    close, adjusted_close, volume, provider, retrieved_at
                )
                select ?, ?, source.id,
                       (select id from securities
                         where symbol = ? and active
                           and is_demo_data = (
                               select data_mode <> 'REAL' from research_jobs where id = ?
                           )
                         order by id limit 1),
                       ?, '1d', ?, ?, ?, ?, ?, ?, ?, source.provider, source.retrieved_at
                  from source_snapshots source
                 where source.id = ?
                on conflict (
                    research_job_id, source_snapshot_id, symbol, interval, observation_date
                ) do nothing
                """, rows);
    }

    private void persistFinancialMetrics(
            QueueClaim claim,
            StoredSource source,
            JsonNode payload
    ) {
        String symbol = payload.path("symbol").asText();
        List<Object[]> rows = new java.util.ArrayList<>();
        payload.path("metrics").forEach(metric -> {
            LocalDate periodEnd = date(metric, "periodEndDate");
            LocalDate filedDate = optionalDate(metric, "filedDate");
            rows.add(new Object[]{
                    UUID.randomUUID(),
                    claim.researchJobId(),
                    symbol,
                    claim.researchJobId(),
                    symbol,
                    text(metric, "periodType", "UNKNOWN"),
                    periodEnd.getYear(),
                    periodEnd,
                    metric.path("name").asText(),
                    decimal(metric, "value"),
                    text(metric, "unit", "UNKNOWN"),
                    filedDate == null ? null : Timestamp.valueOf(filedDate.atStartOfDay()),
                    nullableText(metric, "taxonomy"),
                    nullableText(metric, "concept"),
                    nullableText(metric, "accessionNumber"),
                    metric.path("derived").asBoolean(false),
                    source.id()
            });
        });
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                insert into financial_metrics (
                    id, research_job_id, source_snapshot_id, security_id,
                    symbol, fiscal_period, fiscal_year, period_end_date,
                    metric_name, metric_value, unit, provider, source_url,
                    published_at, retrieved_at, taxonomy, concept,
                    accession_number, is_derived
                )
                select ?, ?, source.id,
                       (select id from securities
                         where symbol = ? and active
                           and is_demo_data = (
                               select data_mode <> 'REAL' from research_jobs where id = ?
                           )
                         order by id limit 1),
                       ?, ?, ?, ?, ?, ?, ?, source.provider, source.source_url,
                       ?, source.retrieved_at, ?, ?, ?, ?
                  from source_snapshots source
                 where source.id = ?
                on conflict (
                    research_job_id, source_snapshot_id, metric_name,
                    fiscal_period, period_end_date
                ) do nothing
                """, rows);
    }

    private void persistMacroSeries(
            QueueClaim claim,
            StoredSource source,
            JsonNode payload
    ) {
        List<Object[]> rows = new java.util.ArrayList<>();
        payload.path("series").forEach(series -> series.path("observations").forEach(
                observation -> rows.add(new Object[]{
                        UUID.randomUUID(),
                        claim.researchJobId(),
                        series.path("seriesId").asText(),
                        text(series, "name", series.path("seriesId").asText()),
                        date(observation, "date"),
                        decimal(observation, "value"),
                        text(series, "unit", "UNKNOWN"),
                        nullableText(series, "frequency"),
                        optionalDate(observation, "realtimeStart"),
                        optionalDate(observation, "realtimeEnd"),
                        source.id()
                })
        ));
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                insert into macro_series (
                    id, research_job_id, source_snapshot_id, series_id,
                    series_name, observation_date, metric_value, unit,
                    frequency, realtime_start, realtime_end, provider, retrieved_at
                )
                select ?, ?, source.id, ?, ?, ?, ?, ?, ?, ?, ?,
                       source.provider, source.retrieved_at
                  from source_snapshots source
                 where source.id = ?
                on conflict (
                    research_job_id, source_snapshot_id, series_id, observation_date
                ) do nothing
                """, rows);
    }

    private StoredSource persistSingleSource(
            QueueClaim claim,
            JsonNode payload,
            String provider,
            String schemaVersion,
            String purpose,
            String externalId,
            LocalDate effectiveDate,
            boolean primary,
            ArrayNode artifactIds
    ) {
        boolean demoData = payload.has("demoData")
                ? payload.path("demoData").asBoolean()
                : payload.path("watermark").asText().contains("DEMO DATA");
        String resolvedProvider = text(payload, "provider", provider);
        String resolvedSchema = text(payload, "schemaVersion", schemaVersion);
        String rawDataHash = nullableText(payload, "rawDataHash");
        String sourceUrl = nullableText(payload, "sourceUrl");
        String retrievedAtText = nullableText(payload, "retrievedAt");
        String freshnessStatus = text(payload, "freshnessStatus", "FRESH");
        SourceRegistration registration = new SourceRegistration(
                resolvedProvider,
                demoData ? "MOCK" : sourceType(purpose),
                resolvedSchema,
                purpose,
                externalId,
                sourceUrl,
                retrievedAtText == null ? null : Instant.parse(retrievedAtText),
                effectiveDate,
                rawDataHash,
                primary,
                freshnessStatus,
                demoData,
                nullableText(payload, "licensePolicyVersion")
        );
        var source = artifactStore.persistSource(
                claim,
                registration,
                payload
        );
        artifactIds.add(source.id().toString());
        return source;
    }

    private static String sourceType(String purpose) {
        return switch (purpose) {
            case "FILING" -> "SEC_FILING";
            case "FUNDAMENTALS" -> "SEC_FILING";
            case "MACRO" -> "GOVERNMENT_DATA";
            case "MARKET_DATA", "BENCHMARK_DATA" -> "MARKET_DATA_PROVIDER";
            default -> "OTHER";
        };
    }

    private static String text(JsonNode payload, String field, String fallback) {
        String value = payload.path(field).asText();
        return value.isBlank() ? fallback : value;
    }

    private static String nullableText(JsonNode payload, String field) {
        String value = payload.path(field).asText();
        return value.isBlank() ? null : value;
    }

    private static LocalDate date(JsonNode payload, String field) {
        String value = payload.path(field).asText();
        if (value.isBlank()) {
            throw new StepExecutionException(
                    "SOURCE_EFFECTIVE_DATE_MISSING",
                    "A deterministic source is missing its effective date",
                    false
            );
        }
        return LocalDate.parse(value);
    }

    private static LocalDate optionalDate(JsonNode payload, String field) {
        String value = payload.path(field).asText();
        return value.isBlank() ? null : LocalDate.parse(value);
    }

    private static java.math.BigDecimal decimal(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        if (!value.isNumber() && !value.isTextual()) {
            throw new StepExecutionException(
                    "NORMALIZED_OBSERVATION_INVALID",
                    "A provider observation is missing a required numeric value",
                    false
            );
        }
        try {
            return value.isNumber()
                    ? value.decimalValue()
                    : new java.math.BigDecimal(value.asText());
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new StepExecutionException(
                    "NORMALIZED_OBSERVATION_INVALID",
                    "A provider observation contains an invalid numeric value",
                    false,
                    exception
            );
        }
    }
}
