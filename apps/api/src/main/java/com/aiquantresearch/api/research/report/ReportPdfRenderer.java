package com.aiquantresearch.api.research.report;

import com.aiquantresearch.api.research.orchestration.StoredEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ReportPdfRenderer {

    private static final String BUNDLED_CJK_FONT =
            "/fonts/ttf/NotoSansSC/NotoSansSC-Regular.ttf";
    private static final Pattern REMOTE_RESOURCE = Pattern.compile(
            "(?is)(url\\s*\\(|@import|<\\s*(?:img|link|script|iframe|object|embed)\\b)"
    );
    private static final List<Path> CJK_FONT_CANDIDATES = List.of(
            Path.of("/usr/share/fonts/noto/NotoSansCJK-Regular.ttc"),
            Path.of("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
            Path.of("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"),
            Path.of("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
            Path.of("/usr/share/fonts/truetype/arphic/uming.ttc"),
            Path.of("/System/Library/Fonts/Supplemental/Arial Unicode.ttf"),
            Path.of("/System/Library/Fonts/PingFang.ttc"),
            Path.of("/System/Library/Fonts/STHeiti Medium.ttc")
    );

    private final ReportHtmlRenderer htmlRenderer;

    public ReportPdfRenderer(ReportHtmlRenderer htmlRenderer) {
        this.htmlRenderer = htmlRenderer;
    }

    public byte[] render(JsonNode report, List<StoredEvidence> evidence) {
        String html = htmlRenderer.render(report, evidence);
        if (REMOTE_RESOURCE.matcher(html).find()) {
            throw new IllegalArgumentException("Report HTML contains a forbidden external resource");
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(output);
            builder.withProducer("AI Quant Research Assistant deterministic offline renderer");
            builder.useExternalResourceAccessControl(
                    (uri, resourceType) -> false,
                    ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI
            );
            if (ReportPdfRenderer.class.getResource(BUNDLED_CJK_FONT) != null) {
                builder.useFont(
                        () -> ReportPdfRenderer.class.getResourceAsStream(BUNDLED_CJK_FONT),
                        "Report CJK",
                        400,
                        FontStyle.NORMAL,
                        true
                );
            } else {
                findCjkFont().ifPresent(font -> builder.useFont(
                        font,
                        "Report CJK",
                        400,
                        FontStyle.NORMAL,
                        true
                ));
            }
            builder.run();
            byte[] pdf = output.toByteArray();
            if (pdf.length < 5
                    || pdf[0] != '%'
                    || pdf[1] != 'P'
                    || pdf[2] != 'D'
                    || pdf[3] != 'F') {
                throw new IllegalStateException("The offline renderer did not produce a PDF document");
            }
            return pdf;
        } catch (IOException exception) {
            throw new IllegalStateException("The report PDF could not be rendered offline", exception);
        }
    }

    private static java.util.Optional<File> findCjkFont() {
        String configured = System.getenv("AI_QUANT_CJK_FONT");
        if (configured != null && !configured.isBlank()) {
            Path candidate = Path.of(configured);
            if (isSupportedFont(candidate)) {
                return java.util.Optional.of(candidate.toFile());
            }
        }
        return CJK_FONT_CANDIDATES.stream()
                .filter(ReportPdfRenderer::isSupportedFont)
                .map(Path::toFile)
                .findFirst();
    }

    private static boolean isSupportedFont(Path path) {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc");
    }
}
