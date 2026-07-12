package com.aiquantresearch.api.research.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiquantresearch.api.research.artifactapi.ArtifactQueryService;
import com.aiquantresearch.api.research.filing.FilingRegistry;
import com.aiquantresearch.api.research.filing.FilingChunkSearchService;
import com.aiquantresearch.api.research.orchestration.StoredSource;
import com.aiquantresearch.api.support.PostgresRedisIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ResourceLock("durable-postgres-queue")
class Phase5EvidenceIT extends PostgresRedisIntegrationTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FilingRegistry filingRegistry;

    @Autowired
    private FilingChunkSearchService filingChunkSearch;

    @Autowired
    private ArtifactQueryService artifactQueryService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID ownerId;
    private UUID researchId;

    @BeforeEach
    void createResearch() {
        ownerId = UUID.randomUUID();
        researchId = UUID.randomUUID();
        jdbc.update("insert into users (id, email) values (?, ?)",
                ownerId, "phase5-" + ownerId + "@example.test");
        jdbc.update("""
                insert into research_jobs (
                    id, user_id, security_id, symbol_input, query, locale, request_json,
                    status, progress, current_step, data_mode, created_by, updated_by
                ) values (?, ?, '00000000-0000-4000-8000-000000000001', 'MU',
                          'Validate Phase 5 Evidence lineage', 'en-US', '{}'::jsonb,
                          'QUEUED', 0, 'RESOLVE_SECURITY', 'MOCK', ?, ?)
                """, researchId, ownerId, ownerId, ownerId);
    }

    @Test
    void migrationCreatesImmutableFilingRegistryAndSearchIndex() {
        assertThat(jdbc.queryForList("""
                select table_name from information_schema.tables
                 where table_schema = 'public'
                   and table_name in (
                     'filings', 'filing_chunks', 'filing_source_snapshot_links'
                   )
                 order by table_name
                """))
                .extracting(row -> row.get("table_name"))
                .containsExactly(
                        "filing_chunks",
                        "filing_source_snapshot_links",
                        "filings"
                );
        assertThat(jdbc.queryForObject("""
                select count(*) from pg_indexes
                 where schemaname = 'public'
                   and indexname = 'ix_filing_chunks_search'
                   and indexdef ilike '%using gin%'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.columns
                 where table_schema = 'public' and table_name = 'claims'
                   and column_name = 'date_references_json'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void filingChunksAreSearchableAndRemainBoundToThePublishedSnapshot() throws Exception {
        JsonNode snapshot = objectMapper.readTree("""
                {
                  "symbol": "MU",
                  "asOfDate": "2025-10-01",
                  "filings": [{
                    "documentId": "mock-mu-10k-2025",
                    "accessionNumber": "0000000001-25-000001",
                    "formType": "10-K",
                    "filingDate": "2025-10-01",
                    "title": "Synthetic annual filing",
                    "summary": "Supply concentration and inventory risk.",
                    "contentHtml": "<html><body><h2>Item 1. Business</h2><p>Memory products.</p><h2>Item 1A. Risk Factors</h2><p>Supply concentration creates inventory risk.</p><h2>Item 7. Management's Discussion and Analysis</h2><p>Demand and margins.</p><h2>Item 8. Financial Statements</h2><p>Audited statements.</p></body></html>"
                  }]
                }
                """);
        UUID sourceId = insertSource("MOCK_FILINGS_V1", snapshot, "FILING");
        UUID evidenceId = insertEvidence(sourceId, "ev_phase5_filing_original");
        StoredSource source = new StoredSource(
                sourceId,
                "FILING",
                "MU",
                snapshot,
                sha256(snapshot.toString()),
                "MOCK_FILINGS_V1",
                true,
                "FRESH",
                true
        );

        filingRegistry.register(source, snapshot);

        assertThat(jdbc.queryForObject(
                "select count(*) from filings where source_snapshot_id = ?",
                Integer.class,
                sourceId
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*)
                  from filing_chunks fc join filings f on f.id = fc.filing_id
                 where f.source_snapshot_id = ?
                """, Integer.class, sourceId)).isGreaterThanOrEqualTo(4);
        var search = artifactQueryService.searchEvidence(
                ownerId,
                researchId,
                "supply inventory risk",
                10
        );
        assertThat(search.items()).singleElement().satisfies(item -> {
            assertThat(item.evidenceId()).isEqualTo("ev_phase5_filing_original");
            assertThat(item.sectionName()).isEqualTo("ITEM_1A_RISK_FACTORS");
            assertThat(item.citationLocator()).contains("chunk=0:chars=");
        });
        assertThat(filingChunkSearch.search(researchId, "supply inventory risk", 10))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.evidenceId()).isEqualTo("ev_phase5_filing_original");
                    assertThat(item.sectionName()).isEqualTo("ITEM_1A_RISK_FACTORS");
                    assertThat(item.excerpt()).contains("Supply concentration");
                    assertThat(item.citationLocator()).contains("chunk=0:chars=");
                });

        UUID refreshedSourceId = insertSource("MOCK_FILINGS_REFRESHED", snapshot, "OTHER");
        filingRegistry.register(new StoredSource(
                refreshedSourceId,
                "FILING",
                "MU",
                snapshot,
                sha256(snapshot.toString()),
                "MOCK_FILINGS_REFRESHED",
                true,
                "FRESH",
                true
        ), snapshot);
        assertThat(jdbc.queryForObject("""
                select count(*) from filings
                 where accession_number = '0000000001-25-000001'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*)
                  from filing_source_snapshot_links link
                  join filings filing on filing.id = link.filing_id
                 where filing.accession_number = '0000000001-25-000001'
                """, Integer.class)).isEqualTo(2);

        UUID reportId = publishReportBoundTo(sourceId, evidenceId);
        String originalContentHash = jdbc.queryForObject(
                "select content_hash from report_versions where id = ?",
                String.class,
                reportId
        );
        JsonNode replacement = objectMapper.readTree("""
                {"symbol":"MU","asOfDate":"2026-01-01","filings":[]}
                """);
        UUID replacementSource = insertSource("MOCK_FILINGS_V2", replacement, "OTHER");

        var published = artifactQueryService.report(ownerId, researchId, 1);
        assertThat(published.report().path("sourceSnapshotId").asText())
                .isEqualTo(sourceId.toString())
                .isNotEqualTo(replacementSource.toString());
        assertThat(published.contentHash()).isEqualTo(originalContentHash);

        UUID filingId = jdbc.queryForObject(
                "select id from filings where source_snapshot_id = ?",
                UUID.class,
                sourceId
        );
        UUID chunkId = jdbc.queryForObject(
                "select id from filing_chunks where filing_id = ? order by chunk_index limit 1",
                UUID.class,
                filingId
        );
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                jdbc.update("update filings set title = 'changed' where id = ?", filingId)
        )).hasMessageContaining("append-only");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                jdbc.update("delete from filing_chunks where id = ?", chunkId)
        )).hasMessageContaining("append-only");
    }

    private UUID insertSource(String provider, JsonNode payload, String purpose) {
        UUID sourceId = UUID.randomUUID();
        String rawHash = sha256(provider + ":raw:" + sourceId);
        String normalizedHash = sha256(payload.toString());
        jdbc.update("""
                insert into source_snapshots (
                    id, provider, source_type, retrieved_at, effective_date,
                    raw_data_hash, normalized_data_hash, payload_json,
                    is_primary_source, freshness_status, is_demo_data, schema_version,
                    metadata_json
                ) values (?, ?, 'MOCK', statement_timestamp(), date '2025-10-01',
                          ?, ?, ?::jsonb, true, 'FRESH', true, 'mock_filings_v1',
                          '{"missingPublishedAtReason":"synthetic fixture"}'::jsonb)
                """, sourceId, provider, rawHash, normalizedHash, payload.toString());
        jdbc.update("""
                insert into research_source_links (
                    research_job_id, source_snapshot_id, purpose
                ) values (?, ?, ?)
                """, researchId, sourceId, purpose);
        return sourceId;
    }

    private UUID insertEvidence(UUID sourceId, String publicId) {
        UUID evidenceId = UUID.randomUUID();
        jdbc.update("""
                insert into evidence_items (
                    id, public_id, research_job_id, source_snapshot_id,
                    evidence_type, title, summary, value_json,
                    quality_score, is_demo_data
                ) values (?, ?, ?, ?, 'SEC_FILING', 'Synthetic filing',
                          'Fixed filing Evidence', '{"asOfDate":"2025-10-01"}'::jsonb,
                          1, true)
                """, evidenceId, publicId, researchId, sourceId);
        return evidenceId;
    }

    private UUID publishReportBoundTo(UUID sourceId, UUID evidenceId) {
        UUID reportId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        ObjectMapper mapper = objectMapper;
        ObjectNodeBuilder report = new ObjectNodeBuilder(mapper)
                .put("schemaVersion", "research_report_v1")
                .put("dataMode", "MOCK")
                .put("sourceSnapshotId", sourceId.toString());
        String reportText = report.json().toString();
        String hash = sha256(reportText);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("""
                    insert into report_versions (
                        id, research_job_id, version, report_schema_version,
                        report_json, report_markdown, validation_status, content_hash,
                        data_mode, data_as_of_date, generated_at
                    ) values (?, ?, 1, 'research_report_v1', ?::jsonb,
                              'DEMO DATA — NOT REAL MARKET DATA', 'PASSED', ?,
                              'MOCK', date '2025-10-01', statement_timestamp())
                    """, reportId, researchId, reportText, hash);
            jdbc.update("""
                    insert into claims (
                        id, public_id, report_version_id, research_job_id,
                        claim_key, claim_type, statement, materiality,
                        confidence, date_references_json, validation_status
                    ) values (?, ?, ?, ?, 'snapshot.bound', 'FACT',
                              'The report binds its original snapshot.', 'MATERIAL',
                              1, '[]'::jsonb, 'PASSED')
                    """, claimId, "cl_" + compact(claimId), reportId, researchId);
            jdbc.update("""
                    insert into claim_evidence_links (
                        claim_id, evidence_id, research_job_id,
                        support_role, relevance_score, citation_locator
                    ) values (?, ?, ?, 'PRIMARY', 1, '/sourceSnapshotId')
                    """, claimId, evidenceId, researchId);
        });
        return reportId;
    }

    private static String compact(UUID id) {
        return id.toString().replace("-", "");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static final class ObjectNodeBuilder {
        private final com.fasterxml.jackson.databind.node.ObjectNode node;

        private ObjectNodeBuilder(ObjectMapper mapper) {
            node = mapper.createObjectNode();
        }

        private ObjectNodeBuilder put(String name, String value) {
            node.put(name, value);
            return this;
        }

        private JsonNode json() {
            return node;
        }
    }
}
