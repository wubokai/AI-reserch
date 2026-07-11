package com.aiquantresearch.api.research.provider.sec;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.providers.sec")
public record SecEdgarProperties(
        @NotNull URI dataBaseUrl,
        @NotNull URI archivesBaseUrl,
        @NotNull URI companyTickersUrl,
        @NotNull Duration timeout,
        String userAgent,
        @Min(1) @Max(10) int maxRequestsPerSecond,
        @Min(1) @Max(20) int maxFilings,
        @Min(1_024) @Max(20_000_000) int maxJsonBytes,
        @Min(1_024) @Max(25_000_000) int maxFilingBytes,
        @Min(1) @Max(4) int maxAttempts
) {

    public SecEdgarProperties {
        userAgent = userAgent == null ? null : userAgent.strip();
    }

    public void requireConfiguredIdentity() {
        if (userAgent == null
                || userAgent.isBlank()
                || !userAgent.contains("@")
                || userAgent.length() > 255) {
            throw new IllegalStateException(
                    "SEC_USER_AGENT must identify the application and a monitored contact email"
            );
        }
    }
}
