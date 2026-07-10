package com.aiquantresearch.api.research.artifactapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
class ReportExportStoreTest {

    private static final UUID OWNER_ID = UUID.fromString(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    );
    private static final UUID RESEARCH_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );
    private static final UUID REPORT_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );

    @Mock
    private JdbcTemplate jdbc;

    private ReportExportStore store;

    @BeforeEach
    void setUp() {
        store = new ReportExportStore(jdbc, new ObjectMapper());
    }

    @Test
    void latestReportQueryIsOwnerScopedAndHidesDeletedAndMixedTestRows() {
        when(jdbc.queryForList(anyString(), eq(RESEARCH_ID), eq(OWNER_ID)))
                .thenReturn(List.of(reportRow()));

        ExportReportSource source = store.findReport(OWNER_ID, RESEARCH_ID, null).orElseThrow();

        assertThat(source.reportVersionId()).isEqualTo(REPORT_ID);
        verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("r.user_id = ?")
                                && sql.contains("r.deleted_at is null")
                                && sql.contains("r.data_mode <> 'MIXED_TEST'")
                                && sql.contains("rv.id = r.latest_report_version_id")
                                && !sql.contains("andrv.")
                                && sql.contains("PASSED_WITH_WARNINGS")
                ),
                eq(RESEARCH_ID),
                eq(OWNER_ID)
        );
    }

    @Test
    void explicitVersionUsesTheRequestedImmutableVersion() {
        when(jdbc.queryForList(anyString(), eq(RESEARCH_ID), eq(OWNER_ID), eq(3)))
                .thenReturn(List.of(reportRow()));

        assertThat(store.findReport(OWNER_ID, RESEARCH_ID, 3)).isPresent();
        verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("rv.version = ?") && !sql.contains("andrv.")),
                eq(RESEARCH_ID),
                eq(OWNER_ID),
                eq(3)
        );
    }

    private static Map<String, Object> reportRow() {
        return Map.of(
                "report_version_id", REPORT_ID,
                "research_job_id", RESEARCH_ID,
                "version", 2,
                "report_json", "{\"schemaVersion\":\"research_report_v1\"}",
                "data_mode", "MOCK",
                "content_hash", "a".repeat(64),
                "symbol", "MU"
        );
    }
}
