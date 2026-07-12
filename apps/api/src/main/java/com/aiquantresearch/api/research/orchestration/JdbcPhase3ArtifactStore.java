package com.aiquantresearch.api.research.orchestration;

import com.aiquantresearch.api.research.application.CanonicalHashService;
import com.aiquantresearch.api.research.llm.LlmCallAudit;
import com.aiquantresearch.api.research.worker.QueueClaim;
import com.aiquantresearch.api.research.worker.StepExecutionException;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPhase3ArtifactStore implements Phase3ArtifactStore {

    private static final Pattern NON_ID = Pattern.compile("[^a-z0-9]+");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CanonicalHashService hashService;
    private final Clock clock;

    public JdbcPhase3ArtifactStore(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            CanonicalHashService hashService,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.hashService = hashService;
        this.clock = clock;
    }

    @Override
    public ResearchExecutionContext context(UUID researchId) {
        var rows = jdbcTemplate.query("""
                select r.id, r.user_id, coalesce(s.symbol, r.symbol_input) as symbol,
                       coalesce(s.security_type, 'COMMON_STOCK') as security_type,
                       r.locale, r.data_mode, r.request_json::text as request_json
                  from research_jobs r
                  left join securities s on s.id = r.security_id
                 where r.id = ? and r.deleted_at is null
                """, (row, ignored) -> new ResearchExecutionContext(
                row.getObject("id", UUID.class),
                row.getObject("user_id", UUID.class),
                row.getString("symbol"),
                row.getString("security_type"),
                row.getString("locale"),
                DataMode.valueOf(row.getString("data_mode")),
                json(row.getString("request_json"))
        ), researchId);
        if (rows.size() != 1) {
            throw new StepExecutionException(
                    "RESEARCH_CONTEXT_NOT_FOUND",
                    "The research execution context is unavailable",
                    false
            );
        }
        return rows.getFirst();
    }

    @Override
    public Optional<StoredSource> source(UUID researchId, String purpose) {
        return jdbcTemplate.query("""
                select s.id, l.purpose, s.external_source_id, s.provider,
                       s.is_primary_source, s.freshness_status, s.is_demo_data,
                       s.payload_json::text as payload_json,
                       s.normalized_data_hash
                  from research_source_links l
                  join source_snapshots s on s.id = l.source_snapshot_id
                 where l.research_job_id = ? and l.purpose = ?
                 order by l.created_at desc, l.id desc
                 limit 1
                """, (row, ignored) -> storedSource(row), researchId, purpose)
                .stream().findFirst();
    }

    @Override
    public List<StoredSource> sources(UUID researchId) {
        return jdbcTemplate.query("""
                select s.id, l.purpose, s.external_source_id, s.provider,
                       s.is_primary_source, s.freshness_status, s.is_demo_data,
                       s.payload_json::text as payload_json,
                       s.normalized_data_hash
                  from research_source_links l
                  join source_snapshots s on s.id = l.source_snapshot_id
                 where l.research_job_id = ?
                 order by l.created_at, l.id
                """, (row, ignored) -> storedSource(row), researchId);
    }

    @Override
    public List<StoredQuantResult> quantResults(UUID researchId) {
        return jdbcTemplate.query("""
                select id, public_id, metric_name, metric_value, unit,
                       result_status, result_json::text as result_json
                  from quant_results
                 where research_job_id = ?
                 order by metric_name, public_id
                """, (row, ignored) -> new StoredQuantResult(
                row.getObject("id", UUID.class),
                row.getString("public_id"),
                row.getString("metric_name"),
                row.getBigDecimal("metric_value"),
                row.getString("unit"),
                row.getString("result_status"),
                json(row.getString("result_json"))
        ), researchId);
    }

    @Override
    public List<StoredEvidence> evidence(UUID researchId) {
        return jdbcTemplate.query("""
                select e.id, e.public_id, e.evidence_type, e.title, e.summary,
                       e.value_json::text as value_json, e.unit,
                       e.source_snapshot_id, e.quant_result_id, e.quality_score,
                       e.is_demo_data,
                       coalesce(ss.is_primary_source, true) as is_primary_source,
                       coalesce(ss.freshness_status, 'FRESH') as freshness_status,
                       coalesce(ss.effective_date, qr.input_data_end) as effective_date,
                       coalesce(ss.provider, 'Internal Analytics') as source_name,
                       ss.source_url,
                       coalesce(ss.source_type, 'INTERNAL_CALCULATION') as source_type,
                       case
                           when nullif(ss.payload_json ->> 'attribution', '') is not null
                               then ss.payload_json ->> 'attribution'
                           when ss.provider like 'SEC_EDGAR%'
                               then 'Data sourced from the U.S. Securities and Exchange Commission (SEC) EDGAR system.'
                           else null
                       end as attribution,
                       ss.metadata_json ->> 'licensePolicyVersion' as license_policy_version
                  from evidence_items e
                  left join source_snapshots ss on ss.id = e.source_snapshot_id
                  left join quant_results qr on qr.id = e.quant_result_id
                 where e.research_job_id = ?
                 order by e.public_id
                """, (row, ignored) -> new StoredEvidence(
                row.getObject("id", UUID.class),
                row.getString("public_id"),
                row.getString("evidence_type"),
                row.getString("title"),
                row.getString("summary"),
                json(row.getString("value_json")),
                row.getString("unit"),
                row.getObject("source_snapshot_id", UUID.class),
                row.getObject("quant_result_id", UUID.class),
                row.getBigDecimal("quality_score"),
                row.getBoolean("is_primary_source"),
                row.getString("freshness_status"),
                row.getObject("effective_date", LocalDate.class),
                row.getBoolean("is_demo_data"),
                row.getString("source_name"),
                row.getString("source_url"),
                row.getString("source_type"),
                row.getString("attribution"),
                row.getString("license_policy_version")
        ), researchId);
    }

    @Override
    public Optional<JsonNode> generatedReport(UUID researchId) {
        return jdbcTemplate.query("""
                select a.output_manifest_json::text as manifest_json
                  from step_attempts a
                  join research_steps s on s.id = a.research_step_id
                 where s.research_job_id = ?
                   and s.step_type = 'GENERATE_REPORT'
                   and a.status = 'SUCCEEDED'
                 order by a.attempt_number desc
                 limit 1
                """, (row, ignored) -> json(row.getString("manifest_json")).path("report"), researchId)
                .stream()
                .filter(node -> node.isObject())
                .findFirst();
    }

    @Override
    public int nextReportVersion(UUID researchId) {
        Integer value = jdbcTemplate.queryForObject(
                "select coalesce(max(version), 0) + 1 from report_versions where research_job_id = ?",
                Integer.class,
                researchId
        );
        return value == null ? 1 : value;
    }

    @Override
    public StoredSource persistSource(
            QueueClaim claim,
            SourceRegistration registration,
            JsonNode payload
    ) {
        String contentHash = hashService.hash(payload);
        String rawDataHash = registration.rawDataHash() == null
                ? contentHash
                : registration.rawDataHash();
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("snapshotPolicyVersion", "source_snapshot_v2");
        metadata.put("rawEqualsNormalized", rawDataHash.equals(contentHash));
        if (registration.licensePolicyVersion() != null) {
            metadata.put("licensePolicyVersion", registration.licensePolicyVersion());
        }
        if (registration.demoData()) {
            metadata.put("missingPublishedAtReason", "synthetic fixture uses its effective date");
        }
        UUID proposedId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into source_snapshots (
                    id, provider, source_type, external_source_id,
                    source_url, request_fingerprint, retrieved_at, effective_date,
                    raw_data_hash, normalized_data_hash, payload_json,
                    is_primary_source, freshness_status, is_demo_data, schema_version,
                    metadata_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb)
                on conflict (
                    provider, raw_data_hash, normalized_data_hash, schema_version
                ) do nothing
                """,
                proposedId,
                registration.provider(),
                registration.sourceType(),
                registration.externalSourceId(),
                registration.sourceUrl(),
                claim.inputHash(),
                Timestamp.from(registration.retrievedAt() == null
                        ? clock.instant()
                        : registration.retrievedAt()),
                registration.effectiveDate(),
                rawDataHash,
                contentHash,
                jsonText(payload),
                registration.primary(),
                registration.freshnessStatus(),
                registration.demoData(),
                registration.schemaVersion(),
                jsonText(metadata)
        );
        UUID sourceId = jdbcTemplate.queryForObject("""
                select id from source_snapshots
                 where provider = ? and raw_data_hash = ?
                   and normalized_data_hash = ? and schema_version = ?
                """, UUID.class, registration.provider(), rawDataHash, contentHash,
                registration.schemaVersion());
        if (sourceId == null) {
            throw new StepExecutionException(
                    "SOURCE_SNAPSHOT_WRITE_FAILED",
                    "A deterministic source snapshot could not be registered",
                    true
            );
        }
        jdbcTemplate.update("""
                insert into research_source_links (
                    research_job_id, source_snapshot_id, step_attempt_id, purpose
                ) values (?, ?, ?, ?)
                on conflict (research_job_id, source_snapshot_id, purpose) do nothing
                """, claim.researchJobId(), sourceId, claim.attemptId(),
                registration.purpose());
        return new StoredSource(
                sourceId,
                registration.purpose(),
                registration.externalSourceId(),
                payload,
                contentHash,
                registration.provider(),
                registration.primary(),
                registration.freshnessStatus(),
                registration.demoData()
        );
    }

    @Override
    public List<StoredQuantResult> persistQuantMetrics(
            QueueClaim claim,
            JsonNode response
    ) {
        List<StoredQuantResult> results = new ArrayList<>();
        boolean demoData = isDemoResearch(claim.researchJobId());
        String inputHash = response.path("inputHash").asText();
        String calculationVersion = response.path("calculationVersion").asText("quant_v1");
        LocalDate periodStart = LocalDate.parse(response.path("periodStart").asText());
        LocalDate periodEnd = LocalDate.parse(response.path("periodEnd").asText());
        for (JsonNode metric : response.path("metrics")) {
            String name = metric.path("name").asText();
            String publicId = publicId(
                    "calc",
                    claim.researchJobId(),
                    name + "_" + inputHash.substring(0, 8)
            );
            String status = metric.path("status").asText();
            BigDecimal value = "AVAILABLE".equals(status)
                    ? new BigDecimal(metric.path("value").asText())
                    : null;
            jdbcTemplate.update("""
                    insert into quant_results (
                        id, public_id, research_job_id, metric_name, metric_value,
                        unit, result_status, result_json, calculation_version,
                        input_hash, input_data_start, input_data_end, sample_size,
                        warnings_json, is_demo_data
                    ) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    on conflict (research_job_id, metric_name, calculation_version, input_hash)
                    do nothing
                    """,
                    UUID.randomUUID(),
                    publicId,
                    claim.researchJobId(),
                    name,
                    value,
                    metric.path("unit").asText(),
                    status,
                    jsonText(metric),
                    calculationVersion,
                    inputHash,
                    periodStart,
                    periodEnd,
                    metric.path("sampleSize").asInt(),
                    jsonText(metric.path("warnings")),
                    demoData
            );
        }
        results.addAll(quantResults(claim.researchJobId()));
        return List.copyOf(results);
    }

    @Override
    public List<StoredEvidence> persistEvidence(
            QueueClaim claim,
            List<EvidenceDraft> drafts
    ) {
        boolean demoData = isDemoResearch(claim.researchJobId());
        for (EvidenceDraft draft : drafts) {
            String publicId = publicId("ev", claim.researchJobId(), draft.key());
            jdbcTemplate.update("""
                    insert into evidence_items (
                        id, public_id, research_job_id, source_snapshot_id,
                        quant_result_id, evidence_type, title, summary,
                        value_json, unit, quality_score, is_demo_data
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                    on conflict (public_id) do nothing
                    """,
                    UUID.randomUUID(),
                    publicId,
                    claim.researchJobId(),
                    draft.sourceSnapshotId(),
                    draft.quantResultId(),
                    draft.evidenceType(),
                    draft.title(),
                    draft.summary(),
                    jsonText(draft.value()),
                    draft.unit(),
                    BigDecimal.valueOf(draft.qualityScore()),
                    demoData
            );
        }
        return evidence(claim.researchJobId());
    }

    @Override
    public UUID persistLlmCall(QueueClaim claim, LlmCallAudit audit) {
        UUID proposedId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into llm_calls (
                    id, research_job_id, step_attempt_id, provider, model_name,
                    prompt_version, schema_version, request_hash, response_hash,
                    input_tokens, output_tokens, cached_tokens, estimated_cost_usd,
                    latency_ms, status, error_code, is_mock,
                    provider_request_id, pricing_version, network_call_count
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (step_attempt_id, request_hash) where step_attempt_id is not null
                do nothing
                """,
                proposedId,
                claim.researchJobId(),
                claim.attemptId(),
                audit.provider(),
                audit.modelName(),
                audit.promptVersion(),
                audit.schemaVersion(),
                audit.requestHash(),
                audit.responseHash(),
                audit.usage().inputTokens(),
                audit.usage().outputTokens(),
                audit.usage().cachedInputTokens(),
                audit.estimatedCostUsd(),
                audit.latencyMs(),
                audit.status(),
                audit.errorCode(),
                audit.mock(),
                audit.providerRequestId(),
                audit.pricingVersion(),
                audit.networkCallCount()
        );
        UUID id = jdbcTemplate.queryForObject("""
                select id from llm_calls
                 where step_attempt_id = ? and request_hash = ?
                """, UUID.class, claim.attemptId(), audit.requestHash());
        if (id == null) {
            throw new StepExecutionException(
                    "LLM_CALL_AUDIT_FAILED",
                    "The report generation audit could not be registered",
                    true
            );
        }
        return id;
    }

    private StoredSource storedSource(java.sql.ResultSet row) throws java.sql.SQLException {
        return new StoredSource(
                row.getObject("id", UUID.class),
                row.getString("purpose"),
                row.getString("external_source_id"),
                json(row.getString("payload_json")),
                row.getString("normalized_data_hash"),
                row.getString("provider"),
                row.getBoolean("is_primary_source"),
                row.getString("freshness_status"),
                row.getBoolean("is_demo_data")
        );
    }

    private boolean isDemoResearch(UUID researchId) {
        String mode = jdbcTemplate.queryForObject(
                "select data_mode from research_jobs where id = ?",
                String.class,
                researchId
        );
        if (mode == null) {
            throw new StepExecutionException(
                    "RESEARCH_CONTEXT_NOT_FOUND",
                    "The research execution context is unavailable",
                    false
            );
        }
        return isDemoMode(mode);
    }

    static boolean isDemoMode(String mode) {
        return !DataMode.REAL.name().equals(mode);
    }

    private String publicId(String prefix, UUID researchId, String key) {
        String normalized = NON_ID.matcher(key.toLowerCase(Locale.ROOT)).replaceAll("_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        if (normalized.length() > 32) {
            normalized = normalized.substring(0, 32);
        }
        String research = researchId.toString().replace("-", "").substring(0, 10);
        String suffix = hashService.hashText(key).substring(0, 8);
        return prefix + "_" + research + "_" + normalized + "_" + suffix;
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new StepExecutionException(
                    "ARTIFACT_JSON_INVALID",
                    "A stored research artifact is invalid",
                    false,
                    exception
            );
        }
    }

    private String jsonText(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new StepExecutionException(
                    "ARTIFACT_JSON_INVALID",
                    "A research artifact could not be serialized",
                    false,
                    exception
            );
        }
    }
}
