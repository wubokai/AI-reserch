package com.aiquantresearch.api.research.artifactapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeastOnce;

import com.aiquantresearch.api.research.application.ResearchNotFoundException;
import com.aiquantresearch.api.shared.config.ApplicationProperties;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class ArtifactQueryServiceTest {

    private static final UUID OWNER_ID = UUID.fromString(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    );
    private static final UUID RESEARCH_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );
    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    @Mock
    private JdbcTemplate jdbc;

    private ArtifactQueryService service;

    @BeforeEach
    void setUp() {
        service = new ArtifactQueryService(
                jdbc,
                new ObjectMapper().findAndRegisterModules(),
                new ApplicationProperties("api", "test", DataMode.MOCK),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void evidenceMapsCanonicalFieldsAndKeepsOwnerAndVisibilityPredicates() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(ownerRow()))
                .thenReturn(List.of(evidenceRow()));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);

        ArtifactApiResponses.EvidencePage response = service.evidence(
                OWNER_ID,
                RESEARCH_ID,
                0,
                20,
                "CALCULATION",
                "INTERNAL_CALCULATION",
                "FRESH",
                true
        );

        assertThat(response.dataMode()).isEqualTo(DataMode.MOCK);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.evidenceId()).isEqualTo("ev_MU_return-01");
            assertThat(item.relatedClaimIds()).containsExactly("cl_MU-return_01");
            assertThat(item.value().decimalValue()).isEqualByComparingTo("0.42");
        });
        verify(jdbc).queryForObject(
                contains("r.user_id = ?"),
                eq(Long.class),
                any(Object[].class)
        );
        verify(jdbc, atLeastOnce()).queryForList(
                contains("r.data_mode <> 'MIXED_TEST'"),
                any(Object[].class)
        );
    }

    @Test
    void mixedOrDeletedResearchIsIndistinguishableFromMissingResearch() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        assertThatThrownBy(() -> service.reports(OWNER_ID, RESEARCH_ID, 0, 20))
                .isInstanceOf(ResearchNotFoundException.class)
                .hasMessageContaining("does not exist or is not accessible");
        verify(jdbc).queryForList(
                contains("r.deleted_at is null"),
                eq(RESEARCH_ID),
                eq(OWNER_ID)
        );
    }

    @Test
    void reportReturnsMetadataAroundUnmodifiedCanonicalJson() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(ownerRow()))
                .thenReturn(List.of(reportRow()));

        ArtifactApiResponses.ReportVersionResponse response = service.report(
                OWNER_ID,
                RESEARCH_ID,
                1
        );

        assertThat(response.version()).isEqualTo(1);
        assertThat(response.report().path("schemaVersion").asText())
                .isEqualTo("research_report_v1");
        assertThat(response.report().path("sections").isArray()).isTrue();
        assertThat(response.contentHash()).hasSize(64);
    }

    @Test
    void reportHistoryBuildsAValidPublishedReportPredicate() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(ownerRow()))
                .thenReturn(List.of(reportSummaryRow()));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);

        ArtifactApiResponses.ReportVersionPage response = service.reports(
                OWNER_ID,
                RESEARCH_ID,
                0,
                20
        );

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.version()).isEqualTo(1);
            assertThat(item.validationStatus()).isEqualTo("PASSED");
        });
        verify(jdbc).queryForObject(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("and rv.validation_status")
                                && !sql.contains("andrv.")),
                eq(Long.class),
                any(Object[].class)
        );
    }

    @Test
    void filingSearchIsOwnerScopedAndReturnsExactChunkLocator() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(ownerRow()))
                .thenReturn(List.of(searchRow()));

        ArtifactApiResponses.EvidenceSearchResponse response = service.searchEvidence(
                OWNER_ID,
                RESEARCH_ID,
                "supply risk",
                10
        );

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.evidenceId()).isEqualTo("ev_MU_filing-01");
            assertThat(item.sectionName()).isEqualTo("ITEM_1A_RISK_FACTORS");
            assertThat(item.citationLocator())
                    .isEqualTo("filing:mock-mu-10k#ITEM_1A_RISK_FACTORS:chunk=0:chars=0-120");
        });
        verify(jdbc).queryForList(
                contains("websearch_to_tsquery"),
                eq("supply risk"),
                eq("supply risk"),
                eq(RESEARCH_ID),
                eq(OWNER_ID),
                eq("supply risk"),
                eq(10)
        );
    }

    @Test
    void mockSecuritySearchEscapesWildcardInput() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        service.searchSecurities("MU_%", null, null, 10);

        verify(jdbc).queryForList(
                contains("is_demo_data = ?"),
                eq(true),
                eq("MU\\_\\%%"),
                eq("MU\\_\\%%"),
                eq("MU_%"),
                eq(10)
        );
    }

    private static Map<String, Object> ownerRow() {
        return Map.of("id", RESEARCH_ID, "data_mode", "MOCK");
    }

    private static Map<String, Object> evidenceRow() {
        var row = new HashMap<String, Object>();
        row.put("evidence_id", "ev_MU_return-01");
        row.put("evidence_type", "QUANT_RESULT");
        row.put("title", "Five year return");
        row.put("summary", "Deterministic result");
        row.put("value_json", "0.42");
        row.put("unit", "ratio");
        row.put("source_name", "Internal Analytics");
        row.put("source_type", "INTERNAL_CALCULATION");
        row.put("retrieved_at", Timestamp.from(NOW));
        row.put("effective_date", LocalDate.parse("2025-12-31"));
        row.put("is_primary_source", true);
        row.put("freshness_status", "FRESH");
        row.put("quality_score", new BigDecimal("0.9600"));
        row.put("raw_data_hash", "a".repeat(64));
        row.put("is_demo_data", true);
        row.put("related_claim_ids_json", "[\"cl_MU-return_01\"]");
        return row;
    }

    private static Map<String, Object> reportRow() {
        return Map.of(
                "research_job_id", RESEARCH_ID,
                "version", 1,
                "validation_status", "PASSED",
                "content_hash", "b".repeat(64),
                "created_at", Timestamp.from(NOW),
                "generated_at", Timestamp.from(NOW.minusSeconds(1)),
                "report_json", """
                        {"schemaVersion":"research_report_v1","sections":[]}
                        """
        );
    }

    private static Map<String, Object> searchRow() {
        return Map.ofEntries(
                Map.entry("evidence_id", "ev_MU_filing-01"),
                Map.entry("filing_id", UUID.fromString("22222222-2222-4222-8222-222222222222")),
                Map.entry("chunk_id", UUID.fromString("33333333-3333-4333-8333-333333333333")),
                Map.entry("external_document_id", "mock-mu-10k"),
                Map.entry("form_type", "10-K"),
                Map.entry("filing_date", LocalDate.parse("2025-10-01")),
                Map.entry("section_name", "ITEM_1A_RISK_FACTORS"),
                Map.entry("chunk_index", 0),
                Map.entry("excerpt", "Supply concentration may create risk."),
                Map.entry("citation_locator", "filing:mock-mu-10k#ITEM_1A_RISK_FACTORS:chunk=0:chars=0-120"),
                Map.entry("rank", new BigDecimal("0.75")),
                Map.entry("is_demo_data", true)
        );
    }

    private static Map<String, Object> reportSummaryRow() {
        return Map.of(
                "research_job_id", RESEARCH_ID,
                "version", 1,
                "title", "MU report",
                "symbol", "MU",
                "data_as_of_date", LocalDate.parse("2023-12-29"),
                "validation_status", "PASSED",
                "data_mode", "MOCK",
                "content_hash", "b".repeat(64),
                "created_at", Timestamp.from(NOW)
        );
    }
}
