package com.aiquantresearch.api.research.analytics;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.analytics")
public record AnalyticsProperties(
        @NotNull URI baseUrl,
        @NotNull Duration timeout
) {
}
