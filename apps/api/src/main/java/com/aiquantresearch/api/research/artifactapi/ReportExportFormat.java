package com.aiquantresearch.api.research.artifactapi;

import com.aiquantresearch.api.research.application.InvalidResearchRequestException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.MediaType;

enum ReportExportFormat {
    MARKDOWN("md", MediaType.parseMediaType("text/markdown;charset=UTF-8"), "markdown-v1"),
    HTML("html", MediaType.parseMediaType("text/html;charset=UTF-8"), "html-v2"),
    PDF("pdf", MediaType.APPLICATION_PDF, "pdf-v2");

    private final String extension;
    private final MediaType mediaType;
    private final String templateVersion;

    ReportExportFormat(String extension, MediaType mediaType, String templateVersion) {
        this.extension = extension;
        this.mediaType = mediaType;
        this.templateVersion = templateVersion;
    }

    static ReportExportFormat fromApiValue(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new InvalidResearchRequestException(
                    "format must be MARKDOWN, HTML, or PDF"
            );
        }
    }

    byte[] encode(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    String extension() {
        return extension;
    }

    MediaType mediaType() {
        return mediaType;
    }

    String templateVersion() {
        return templateVersion;
    }
}
