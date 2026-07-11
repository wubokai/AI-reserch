package com.aiquantresearch.api.research.report;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.exports")
public record ReportExportProperties(
        @Min(1_048_576) @Max(26_214_400) int maxPdfBytes,
        @Min(1) @Max(200) int maxPdfPages,
        @Min(1_048_576) @Max(52_428_800) int maxFontBytes
) {
}
