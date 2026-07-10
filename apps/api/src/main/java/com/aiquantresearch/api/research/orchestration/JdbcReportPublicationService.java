package com.aiquantresearch.api.research.orchestration;

import com.aiquantresearch.api.research.application.CanonicalHashService;
import com.aiquantresearch.api.research.report.ReportMarkdownRenderer;
import com.aiquantresearch.api.research.worker.DurableQueueClient;
import com.aiquantresearch.api.research.worker.QueueClaim;
import com.aiquantresearch.api.research.worker.StepExecutionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JdbcReportPublicationService implements ReportPublicationService {

    private final JdbcTemplate jdbcTemplate;
    private final Phase3ArtifactStore artifactStore;
    private final DurableQueueClient queueClient;
    private final ReportMarkdownRenderer markdownRenderer;
    private final CanonicalHashService hashService;
    private final ObjectMapper objectMapper;

    public JdbcReportPublicationService(
            JdbcTemplate jdbcTemplate,
            Phase3ArtifactStore artifactStore,
            DurableQueueClient queueClient,
            ReportMarkdownRenderer markdownRenderer,
            CanonicalHashService hashService,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.artifactStore = artifactStore;
        this.queueClient = queueClient;
        this.markdownRenderer = markdownRenderer;
        this.hashService = hashService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void publish(
            QueueClaim claim,
            StepExecutionResult result,
            String outputHash,
            ObjectNode outputManifest
    ) {
        PublicationJob job = lockPublicationJob(claim.researchJobId());
        if (job.cancellationRequested()) {
            throw new StepExecutionException(
                    "RESEARCH_CANCELLATION_REQUESTED",
                    "Research cancellation won the publication race",
                    false
            );
        }
        if (!"VALIDATING_REPORT".equals(job.status())) {
            throw new StepExecutionException(
                    "REPORT_PUBLICATION_STATE_INVALID",
                    "The research is not at the report publication boundary",
                    false
            );
        }
        if (!"MOCK".equals(job.dataMode())) {
            throw new StepExecutionException(
                    "REPORT_DATA_MODE_BLOCKED",
                    "Phase 3 publishes only deterministic Mock reports",
                    false
            );
        }

        JsonNode report = result.payload();
        List<StoredEvidence> evidence = artifactStore.evidence(claim.researchJobId());
        String markdown = markdownRenderer.render(report, evidence);
        String contentHash = hashService.hash(report);
        int version = artifactStore.nextReportVersion(claim.researchJobId());
        UUID llmCallId = generationCallId(claim.researchJobId());
        UUID reportVersionId = UUID.randomUUID();
        String validationStatus = result.partial() || !result.warnings().isEmpty()
                ? "PASSED_WITH_WARNINGS"
                : "PASSED";

        jdbcTemplate.update("""
                insert into report_versions (
                    id, research_job_id, version, report_schema_version,
                    report_json, report_markdown, validation_status, content_hash,
                    data_mode, data_as_of_date, generation_llm_call_id, generated_at
                ) values (?, ?, ?, 'research_report_v1', ?::jsonb, ?, ?, ?,
                          'MOCK', ?, ?, statement_timestamp())
                """,
                reportVersionId,
                claim.researchJobId(),
                version,
                jsonText(report),
                markdown,
                validationStatus,
                contentHash,
                LocalDate.parse(report.path("asOfDate").asText()),
                llmCallId
        );

        Map<String, UUID> evidenceIds = new LinkedHashMap<>();
        evidence.forEach(item -> evidenceIds.put(item.publicId(), item.id()));
        for (JsonNode reportClaim : collectClaims(report).values()) {
            persistClaim(
                    reportVersionId,
                    claim.researchJobId(),
                    reportClaim,
                    evidenceIds,
                    result.warnings()
            );
        }

        ObjectNode runManifest = objectMapper.createObjectNode();
        runManifest.put("schemaVersion", "research_run_manifest_v1");
        runManifest.put("researchId", claim.researchJobId().toString());
        runManifest.put("executionCycle", version);
        runManifest.put("reportVersionId", reportVersionId.toString());
        runManifest.put("reportVersion", version);
        runManifest.put("reportContentHash", contentHash);
        runManifest.put("validateStepOutputHash", outputHash);
        runManifest.put("partial", result.partial());
        runManifest.set("warnings", objectMapper.valueToTree(result.warnings()));
        runManifest.set(
                "sourceSnapshotIds",
                objectMapper.valueToTree(artifactStore.sources(claim.researchJobId()).stream()
                        .map(value -> value.id().toString()).sorted().toList())
        );
        runManifest.set(
                "calculationIds",
                objectMapper.valueToTree(artifactStore.quantResults(claim.researchJobId()).stream()
                        .map(StoredQuantResult::publicId).sorted().toList())
        );
        runManifest.set(
                "evidenceIds",
                objectMapper.valueToTree(evidence.stream()
                        .map(StoredEvidence::publicId).sorted().toList())
        );
        String runManifestHash = hashService.hash(runManifest);
        UUID runManifestId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into research_run_manifests (
                    id, research_job_id, execution_cycle, report_version_id,
                    manifest_json, content_hash, completion_policy_version,
                    data_mode, status
                ) values (?, ?, ?, ?, ?::jsonb, ?, 'phase3_completion_policy_v1',
                          'MOCK', 'PUBLISHED')
                """,
                runManifestId,
                claim.researchJobId(),
                version,
                reportVersionId,
                jsonText(runManifest),
                runManifestHash
        );

        outputManifest.put("reportVersionId", reportVersionId.toString());
        outputManifest.put("reportVersion", version);
        outputManifest.put("reportContentHash", contentHash);
        outputManifest.put("runManifestId", runManifestId.toString());
        var completion = queueClient.completeAndAdvance(
                claim.attemptId(),
                claim.leaseToken(),
                outputHash,
                outputManifest
        );
        if (!completion.committed() || completion.nextResearchStepId() != null) {
            throw new StepExecutionException(
                    "REPORT_QUEUE_COMMIT_REJECTED",
                    "The final durable step could not be committed safely",
                    false
            );
        }

        String terminalStatus = result.partial() ? "PARTIALLY_COMPLETED" : "COMPLETED";
        int updated = jdbcTemplate.update("""
                update research_jobs
                   set latest_report_version_id = ?, status = ?, progress = 100,
                       current_step = null, completed_at = statement_timestamp(),
                       row_version = row_version + 1, updated_by = ?
                 where id = ? and status = 'VALIDATING_REPORT'
                   and not cancellation_requested and deleted_at is null
                """,
                reportVersionId,
                terminalStatus,
                job.ownerId(),
                claim.researchJobId()
        );
        if (updated != 1) {
            throw new StepExecutionException(
                    "REPORT_PUBLICATION_RACE_LOST",
                    "The report publication transaction lost a cancellation or state race",
                    false
            );
        }

        ObjectNode event = objectMapper.createObjectNode();
        event.put("researchId", claim.researchJobId().toString());
        event.put("reportVersionId", reportVersionId.toString());
        event.put("reportVersion", version);
        event.put("contentHash", contentHash);
        event.put("status", terminalStatus);
        jdbcTemplate.update("""
                insert into outbox_events (
                    aggregate_type, aggregate_id, event_type, payload_json
                ) values ('REPORT_VERSION', ?, 'REPORT_PUBLISHED', ?::jsonb)
                """, reportVersionId, jsonText(event));
        jdbcTemplate.update("""
                insert into audit_events (
                    research_job_id, actor_type, action, metadata_json
                ) values (?, 'WORKER', 'REPORT_PUBLISHED', ?::jsonb)
                """, claim.researchJobId(), jsonText(event));
    }

    private void persistClaim(
            UUID reportVersionId,
            UUID researchId,
            JsonNode claim,
            Map<String, UUID> evidenceIds,
            List<String> validationWarnings
    ) {
        UUID claimId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into claims (
                    id, public_id, report_version_id, research_job_id,
                    claim_key, claim_type, statement, materiality, confidence,
                    calculation_ids_json, numeric_references_json, limitations_json,
                    date_references_json, validation_status, validation_notes_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb,
                          ?::jsonb, ?::jsonb, 'PASSED', ?::jsonb)
                """,
                claimId,
                claim.path("id").asText(),
                reportVersionId,
                researchId,
                claim.path("id").asText(),
                claim.path("claimType").asText(),
                claim.path("statement").asText(),
                claim.path("materiality").asText(),
                claim.path("confidence").decimalValue(),
                jsonText(claim.path("calculationIds")),
                jsonText(claim.path("numericReferences")),
                jsonText(claim.path("limitations")),
                jsonText(claim.path("dateReferences")),
                jsonText(objectMapper.valueToTree(validationWarnings))
        );
        int index = 0;
        for (JsonNode evidenceIdNode : claim.path("evidenceIds")) {
            String publicId = evidenceIdNode.asText();
            UUID evidenceId = evidenceIds.get(publicId);
            if (evidenceId == null) {
                throw new StepExecutionException(
                        "REPORT_EVIDENCE_NOT_ALLOWED",
                        "A report Claim references Evidence outside this research",
                        false
                );
            }
            jdbcTemplate.update("""
                    insert into claim_evidence_links (
                        claim_id, evidence_id, research_job_id, support_role,
                        relevance_score, citation_locator
                    ) values (?, ?, ?, ?, ?, ?)
                    """,
                    claimId,
                    evidenceId,
                    researchId,
                    index == 0 ? "PRIMARY" : "SUPPORTING",
                    index == 0 ? BigDecimal.ONE : new BigDecimal("0.9000"),
                    "claim:" + claim.path("id").asText()
            );
            index++;
        }
    }

    private PublicationJob lockPublicationJob(UUID researchId) {
        var rows = jdbcTemplate.query("""
                select user_id, data_mode, status, cancellation_requested
                  from research_jobs
                 where id = ? and deleted_at is null
                 for update
                """, (row, ignored) -> new PublicationJob(
                row.getObject("user_id", UUID.class),
                row.getString("data_mode"),
                row.getString("status"),
                row.getBoolean("cancellation_requested")
        ), researchId);
        if (rows.size() != 1) {
            throw new StepExecutionException(
                    "RESEARCH_CONTEXT_NOT_FOUND",
                    "The research publication context is unavailable",
                    false
            );
        }
        return rows.getFirst();
    }

    private UUID generationCallId(UUID researchId) {
        var values = jdbcTemplate.query("""
                select (a.output_manifest_json ->> 'llmCallId')::uuid as llm_call_id
                  from step_attempts a
                  join research_steps s on s.id = a.research_step_id
                 where s.research_job_id = ? and s.step_type = 'GENERATE_REPORT'
                   and a.status = 'SUCCEEDED'
                 order by a.attempt_number desc
                 limit 1
                """, (row, ignored) -> row.getObject("llm_call_id", UUID.class), researchId);
        if (values.size() != 1 || values.getFirst() == null) {
            throw new StepExecutionException(
                    "LLM_CALL_AUDIT_MISSING",
                    "The deterministic report generation audit is unavailable",
                    false
            );
        }
        return values.getFirst();
    }

    private static Map<String, JsonNode> collectClaims(JsonNode report) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        collectClaimNodes(report, result);
        if (result.isEmpty()) {
            throw new StepExecutionException(
                    "REPORT_CLAIMS_EMPTY",
                    "A validated report must contain at least one Claim",
                    false
            );
        }
        return result;
    }

    private static void collectClaimNodes(JsonNode node, Map<String, JsonNode> result) {
        if (node == null) {
            return;
        }
        if (node.isObject()
                && node.hasNonNull("id")
                && node.hasNonNull("statement")
                && node.hasNonNull("claimType")) {
            String id = node.path("id").asText();
            JsonNode previous = result.putIfAbsent(id, node);
            if (previous != null && !previous.equals(node)) {
                throw new StepExecutionException(
                        "DUPLICATE_CLAIM_ID",
                        "The validated report contains conflicting Claim IDs",
                        false
                );
            }
            return;
        }
        if (node.isContainerNode()) {
            node.elements().forEachRemaining(value -> collectClaimNodes(value, result));
        }
    }

    private String jsonText(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new StepExecutionException(
                    "REPORT_SERIALIZATION_FAILED",
                    "A validated report artifact could not be serialized",
                    false,
                    exception
            );
        }
    }

    private record PublicationJob(
            UUID ownerId,
            String dataMode,
            String status,
            boolean cancellationRequested
    ) {
    }
}
