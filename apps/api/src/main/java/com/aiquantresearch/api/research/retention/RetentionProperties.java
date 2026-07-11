package com.aiquantresearch.api.research.retention;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.retention")
public record RetentionProperties(
        boolean enabled,
        @Min(365) @Max(3_650) int researchArtifactDays,
        @Min(30) @Max(3_650) int providerRawDays,
        @Min(7) @Max(365) int backupDays,
        @Min(1) @Max(1_000) int batchSize,
        @NotNull Duration initialDelay,
        @NotNull Duration sweepDelay
) {
}
