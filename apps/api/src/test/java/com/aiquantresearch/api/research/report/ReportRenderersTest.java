package com.aiquantresearch.api.research.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiquantresearch.api.research.orchestration.StoredEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ReportRenderersTest {

    private final ReportTestFixture fixture = new ReportTestFixture();

    @Test
    void markdownAndHtmlEscapeUntrustedReportAndEvidenceText() {
        ObjectNode report = fixture.report().deepCopy();
        report.put("title", "<script src=\"https://attacker.invalid/x.js\">bad</script>");
        List<StoredEvidence> evidence = new ArrayList<>(fixture.evidence());
        StoredEvidence original = evidence.get(0);
        evidence.set(0, new StoredEvidence(
                original.id(),
                original.publicId(),
                original.evidenceType(),
                "<img src=x onerror=alert(1)>",
                "[unsafe](https://attacker.invalid)",
                original.value(),
                original.unit(),
                original.sourceSnapshotId(),
                original.quantResultId()
        ));

        String markdown = new ReportMarkdownRenderer().render(report, evidence);
        String html = new ReportHtmlRenderer().render(report, evidence);

        assertThat(markdown)
                .contains("&lt;script")
                .doesNotContain("<script")
                .contains(DeterministicMockReportGenerator.DEMO_WATERMARK)
                .contains("## Sources", "## Disclaimer");
        assertThat(html)
                .contains("&lt;script src=&quot;https://attacker.invalid/x.js&quot;&gt;")
                .contains("&lt;img src=x onerror=alert(1)&gt;")
                .doesNotContain("<script", "<img")
                .contains(DeterministicMockReportGenerator.DEMO_WATERMARK)
                .contains("<h2>Sources</h2>", "<h2>Disclaimer</h2>");
    }

    @Test
    void pdfIsOfflineSelfContainedAndContainsRequiredDisclosure() throws IOException {
        JsonNode report = fixture.report();
        ReportPdfRenderer renderer = new ReportPdfRenderer(new ReportHtmlRenderer());

        byte[] pdf = renderer.render(report, fixture.evidence());

        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(new String(pdf, StandardCharsets.ISO_8859_1))
                .doesNotContain("/URI", "http://", "https://");
        Path artifact = Path.of("target", "report-test", "research-report.pdf");
        Files.createDirectories(artifact.getParent());
        Files.write(artifact, pdf);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains("DEMO DATA")
                    .contains("Sources")
                    .contains("Disclaimer")
                    .contains("ev_market_snapshot")
                    .contains("不构成投资建议");
        }
    }

    @Test
    void realReportCarriesFredAndSecAttributionAcrossEveryExport() throws IOException {
        ObjectNode report = fixture.report().deepCopy();
        report.put("dataMode", "REAL");
        report.put("title", "NVDA source-backed research report");
        report.put("disclaimer", "Research use only; not investment advice.");
        report.path("sections").forEach(section ->
                ((ObjectNode) section).put("transitionText", "Source-backed section."));
        JsonNode value = fixture.objectMapper().createObjectNode().put("asOfDate", "2026-07-10");
        List<StoredEvidence> evidence = List.of(
                realEvidence(
                        "ev_fred_macro",
                        "FRED",
                        "GOVERNMENT_DATA",
                        "https://fred.stlouisfed.org/fred/",
                        "This product uses the FRED® API but is not endorsed or certified by the Federal Reserve Bank of St. Louis.",
                        "fred_api_terms_2025-02-18",
                        value
                ),
                realEvidence(
                        "ev_sec_filing",
                        "SEC_EDGAR",
                        "SEC_FILING",
                        "https://data.sec.gov/submissions/CIK0000000001.json",
                        "Data sourced from the U.S. Securities and Exchange Commission (SEC) EDGAR system.",
                        "sec_edgar_access_policy_2025-02-18",
                        value
                )
        );

        String markdown = new ReportMarkdownRenderer().render(report, evidence);
        String html = new ReportHtmlRenderer().render(report, evidence);
        byte[] pdf = new ReportPdfRenderer(new ReportHtmlRenderer()).render(report, evidence);

        assertThat(markdown)
                .contains("## Data source attribution", "FRED", "SEC\\_EDGAR",
                        "fred\\_api\\_terms")
                .doesNotContain(DeterministicMockReportGenerator.DEMO_WATERMARK);
        assertThat(html)
                .contains("<h2>Data source attribution</h2>", "FRED", "SEC_EDGAR")
                .doesNotContain(DeterministicMockReportGenerator.DEMO_WATERMARK, "<a href=");
        assertThat(new String(pdf, StandardCharsets.ISO_8859_1)).doesNotContain("/URI");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(document))
                    .contains("Data source attribution", "FRED", "SEC_EDGAR", "U.S. Securities")
                    .doesNotContain("DEMO DATA");
        }
    }

    private StoredEvidence realEvidence(
            String publicId,
            String provider,
            String sourceType,
            String sourceUrl,
            String attribution,
            String policy,
            JsonNode value
    ) {
        return new StoredEvidence(
                UUID.nameUUIDFromBytes(publicId.getBytes(StandardCharsets.UTF_8)),
                publicId,
                "OTHER",
                provider + " source",
                "Immutable provider evidence.",
                value,
                null,
                UUID.nameUUIDFromBytes((publicId + "-source").getBytes(StandardCharsets.UTF_8)),
                null,
                new BigDecimal("0.9900"),
                true,
                "FRESH",
                LocalDate.parse("2026-07-10"),
                false,
                provider,
                sourceUrl,
                sourceType,
                attribution,
                policy
        );
    }
}
