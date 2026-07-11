package com.aiquantresearch.api.research.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiquantresearch.api.research.domain.StepType;
import com.aiquantresearch.api.research.filing.FilingRegistry;
import com.aiquantresearch.api.research.orchestration.Phase3ArtifactStore;
import com.aiquantresearch.api.research.orchestration.SourceRegistration;
import com.aiquantresearch.api.research.worker.QueueClaim;
import com.aiquantresearch.api.support.PostgresRedisIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ResourceLock("durable-postgres-queue")
class Phase7ProviderLineageIT extends PostgresRedisIntegrationTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Phase3ArtifactStore artifactStore;

    @Autowired
    private FilingRegistry filingRegistry;

    private UUID researchId;

    @BeforeEach
    void createRealResearch() {
        UUID ownerId = UUID.randomUUID();
        researchId = UUID.randomUUID();
        jdbc.update("insert into users (id, email) values (?, ?)",
                ownerId, "phase7-" + ownerId + "@example.test");
        jdbc.update("""
                insert into research_jobs (
                    id, user_id, symbol_input, query, locale, request_json,
                    status, progress, current_step, data_mode, created_by, updated_by
                ) values (?, ?, 'MU', 'Validate real provider lineage', 'en-US', '{}'::jsonb,
                          'QUEUED', 0, 'FETCH_FILINGS', 'REAL', ?, ?)
                """, researchId, ownerId, ownerId, ownerId);
    }

    @Test
    void persistsSecRawLineageAndRegistersTheOfficialDocumentUrl() throws Exception {
        JsonNode snapshot = objectMapper.readTree("""
                {
                  "provider":"SEC_EDGAR",
                  "schemaVersion":"sec_filings_v1",
                  "symbol":"MU",
                  "asOfDate":"2025-10-03",
                  "retrievedAt":"2026-07-10T12:00:00Z",
                  "sourceUrl":"https://data.sec.gov/submissions/CIK0000000123.json",
                  "rawDataHash":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                  "demoData":false,
                  "freshnessStatus":"VERY_STALE",
                  "licensePolicyVersion":"sec_public_edgar_2025_04_08",
                  "filings":[{
                    "documentId":"0000000123-25-000001:report.htm",
                    "accessionNumber":"0000000123-25-000001",
                    "formType":"10-K",
                    "filingDate":"2025-10-03",
                    "reportPeriod":"2025-08-28",
                    "title":"10-K — Micron Test Corp",
                    "summary":"Registered filing evidence.",
                    "sourceUrl":"https://www.sec.gov/Archives/edgar/data/123/000000012325000001/report.htm",
                    "contentHtml":"<html><body><h2>Item 1. Business</h2><p>Registered filing evidence.</p></body></html>"
                  }]
                }
                """);
        SourceRegistration registration = new SourceRegistration(
                "SEC_EDGAR",
                "SEC_FILING",
                "sec_filings_v1",
                "FILING",
                "MU",
                "https://data.sec.gov/submissions/CIK0000000123.json",
                Instant.parse("2026-07-10T12:00:00Z"),
                LocalDate.parse("2025-10-03"),
                "d".repeat(64),
                true,
                "VERY_STALE",
                false,
                "sec_public_edgar_2025_04_08"
        );
        QueueClaim claim = new QueueClaim(
                researchId,
                null,
                null,
                1,
                null,
                null,
                StepType.FETCH_FILINGS,
                "e".repeat(64),
                "sec-edgar-v1",
                1,
                objectMapper.createObjectNode()
        );

        var source = artifactStore.persistSource(claim, registration, snapshot);
        filingRegistry.register(source, snapshot);

        assertThat(jdbc.queryForMap("""
                select provider, source_type, source_url, raw_data_hash,
                       normalized_data_hash, freshness_status, is_demo_data,
                       metadata_json ->> 'licensePolicyVersion' as license_policy_version
                  from source_snapshots where id = ?
                """, source.id()))
                .containsEntry("provider", "SEC_EDGAR")
                .containsEntry("source_type", "SEC_FILING")
                .containsEntry("source_url",
                        "https://data.sec.gov/submissions/CIK0000000123.json")
                .containsEntry("raw_data_hash", "d".repeat(64))
                .containsEntry("freshness_status", "VERY_STALE")
                .containsEntry("is_demo_data", false)
                .containsEntry("license_policy_version", "sec_public_edgar_2025_04_08");
        assertThat(jdbc.queryForObject(
                "select normalized_data_hash <> raw_data_hash from source_snapshots where id = ?",
                Boolean.class,
                source.id()
        )).isTrue();
        assertThat(jdbc.queryForObject(
                "select raw_text_uri from filings where source_snapshot_id = ?",
                String.class,
                source.id()
        )).isEqualTo(
                "https://www.sec.gov/Archives/edgar/data/123/000000012325000001/report.htm"
        );
    }
}
