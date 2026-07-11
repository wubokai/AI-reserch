package com.aiquantresearch.api.research.artifactapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aiquantresearch.api.research.orchestration.StoredEvidence;
import com.aiquantresearch.api.research.report.ReportHtmlRenderer;
import com.aiquantresearch.api.research.report.ReportMarkdownRenderer;
import com.aiquantresearch.api.research.report.ReportPdfRenderer;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportExportServiceTest {

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
    private ReportExportStore store;

    @Mock
    private ReportMarkdownRenderer markdownRenderer;

    @Mock
    private ReportHtmlRenderer htmlRenderer;

    @Mock
    private ReportPdfRenderer pdfRenderer;

    private ReportExportService service;
    private ExportReportSource source;

    @BeforeEach
    void setUp() throws Exception {
        JsonNode report = new ObjectMapper().readTree("""
                {"schemaVersion":"research_report_v1","symbol":"MU"}
                """);
        source = new ExportReportSource(
                REPORT_ID,
                RESEARCH_ID,
                2,
                report,
                DataMode.MOCK,
                "a".repeat(64),
                "MU"
        );
        service = new ReportExportService(store, markdownRenderer, htmlRenderer, pdfRenderer);
    }

    @Test
    void repeatedRequestReturnsTheExactCachedBytesWithoutRenderingAgain() throws Exception {
        byte[] cachedBytes = "cached markdown".getBytes(StandardCharsets.UTF_8);
        when(store.findReport(OWNER_ID, RESEARCH_ID, 2)).thenReturn(Optional.of(source));
        when(store.cached(REPORT_ID, ReportExportFormat.MARKDOWN, "markdown-v2"))
                .thenReturn(Optional.of(new CachedReportExport(cachedBytes, hash(cachedBytes))));

        ReportExportArtifact result = service.export(
                OWNER_ID,
                RESEARCH_ID,
                ReportExportFormat.MARKDOWN,
                2
        );

        assertThat(result.content()).containsExactly(cachedBytes);
        assertThat(result.filename()).isEqualTo("MU-research-v2.md");
        assertThat(result.contentHash()).isEqualTo(hash(cachedBytes));
        verifyNoInteractions(markdownRenderer, htmlRenderer, pdfRenderer);
        verify(store, never()).evidence(any(), any());
    }

    @Test
    void cacheMissRendersFromTheSameReportAndEvidenceAndPersistsBytes() {
        List<StoredEvidence> evidence = List.of(new StoredEvidence(
                UUID.randomUUID(),
                "ev_MU_return-01",
                "QUANT_RESULT",
                "Return",
                "Deterministic result",
                source.report().path("symbol"),
                null,
                null,
                UUID.randomUUID()
        ));
        byte[] rendered = "# MU report".getBytes(StandardCharsets.UTF_8);
        when(store.findReport(OWNER_ID, RESEARCH_ID, null)).thenReturn(Optional.of(source));
        when(store.cached(REPORT_ID, ReportExportFormat.MARKDOWN, "markdown-v2"))
                .thenReturn(Optional.empty());
        when(store.evidence(REPORT_ID, RESEARCH_ID)).thenReturn(evidence);
        when(markdownRenderer.render(source.report(), evidence)).thenReturn("# MU report");
        when(store.cache(
                eq(source),
                eq(ReportExportFormat.MARKDOWN),
                eq("markdown-v2"),
                eq(rendered),
                anyString()
        )).thenAnswer(invocation -> new CachedReportExport(
                rendered,
                invocation.getArgument(4, String.class)
        ));

        ReportExportArtifact result = service.export(
                OWNER_ID,
                RESEARCH_ID,
                ReportExportFormat.MARKDOWN,
                null
        );

        assertThat(result.content()).containsExactly(rendered);
        assertThat(result.contentHash()).matches("^[0-9a-f]{64}$");
        verify(markdownRenderer).render(source.report(), evidence);
        verifyNoInteractions(htmlRenderer, pdfRenderer);
    }

    @Test
    void pdfFailureReturnsASafeErrorAndNeverWritesACacheEntry() {
        when(store.findReport(OWNER_ID, RESEARCH_ID, 2)).thenReturn(Optional.of(source));
        when(store.cached(REPORT_ID, ReportExportFormat.PDF, "pdf-v3"))
                .thenReturn(Optional.empty());
        when(store.evidence(REPORT_ID, RESEARCH_ID)).thenReturn(List.of());
        when(pdfRenderer.render(source.report(), List.of()))
                .thenThrow(new IllegalStateException("font path /private/sensitive failed"));

        assertThatThrownBy(() -> service.export(
                OWNER_ID,
                RESEARCH_ID,
                ReportExportFormat.PDF,
                2
        )).isInstanceOf(ReportExportException.class)
                .hasMessage("The requested report format could not be rendered")
                .hasMessageNotContaining("/private/sensitive");
        verify(store, never()).cache(any(), any(), anyString(), any(), anyString());
    }

    private static String hash(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
