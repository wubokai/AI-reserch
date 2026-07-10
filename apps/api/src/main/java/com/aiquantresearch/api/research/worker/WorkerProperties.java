package com.aiquantresearch.api.research.worker;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.worker")
public record WorkerProperties(
        boolean enabled,
        @NotBlank String workerId,
        @Min(1) @Max(32) int concurrency,
        @NotNull Duration pollDelay,
        @NotNull Duration heartbeatDelay,
        @NotNull Duration reaperDelay,
        @NotNull Duration leaseDuration,
        @Min(1) @Max(3600) int retryBaseDelaySeconds,
        @Min(1) @Max(86400) int retryMaxDelaySeconds
) {
    public WorkerProperties {
        if (leaseDuration != null
                && (leaseDuration.compareTo(Duration.ofSeconds(5)) < 0
                || leaseDuration.compareTo(Duration.ofHours(1)) > 0)) {
            throw new IllegalArgumentException("worker leaseDuration must be between 5s and 1h");
        }
        if (retryMaxDelaySeconds < retryBaseDelaySeconds) {
            throw new IllegalArgumentException(
                    "worker retryMaxDelaySeconds cannot be below retryBaseDelaySeconds"
            );
        }
    }
}
