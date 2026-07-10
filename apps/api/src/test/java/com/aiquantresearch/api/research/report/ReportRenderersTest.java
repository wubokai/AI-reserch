package com.aiquantresearch.api.research.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiquantresearch.api.research.orchestration.StoredEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
}
