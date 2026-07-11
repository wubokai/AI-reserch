package com.aiquantresearch.api.research.provider.fred;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.providers.fred")
public record FredProperties(
        @NotNull URI baseUrl,
        @NotNull Duration timeout,
        String apiKey,
        String userAgent,
        List<String> seriesIds,
        @NotNull LocalDate observationStart,
        @Min(1) @Max(100_000) int maxObservations,
        @Min(1_024) @Max(20_000_000) int maxResponseBytes,
        @Min(1) @Max(10) int maxRequestsPerSecond,
        @Min(1) @Max(4) int maxAttempts
) {

    public FredProperties {
        apiKey = normalize(apiKey);
        userAgent = normalize(userAgent);
        seriesIds = seriesIds == null
                ? List.of()
                : seriesIds.stream().map(FredProperties::normalize).toList();
    }

    public void requireConfiguredAccess() {
        if (apiKey == null || !apiKey.matches("^[a-z0-9]{32}$")) {
            throw new IllegalStateException("FRED_API_KEY must be a valid registered API key");
        }
        if (userAgent == null || userAgent.length() > 255) {
            throw new IllegalStateException("FRED_USER_AGENT must identify the application");
        }
        if (seriesIds.isEmpty() || seriesIds.size() > 10
                || seriesIds.stream().anyMatch(value ->
                        value == null || !value.matches("^[A-Za-z0-9_-]{1,64}$"))) {
            throw new IllegalStateException("FRED_SERIES_IDS contains an invalid series ID");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
