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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
            case FETCH_FUNDAMENTALS -> persistSingleSource(
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
            case FETCH_MACRO_DATA -> persistSingleSource(
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
        persistSingleSource(
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
        persistSingleSource(
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
        var source = artifactStore.persistSource(
                claim,
                provider,
                schemaVersion,
                purpose,
                externalId,
                effectiveDate,
                payload,
                primary
        );
        artifactIds.add(source.id().toString());
        return source;
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
}
