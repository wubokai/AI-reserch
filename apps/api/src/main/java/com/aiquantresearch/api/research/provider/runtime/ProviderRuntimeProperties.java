package com.aiquantresearch.api.research.provider.runtime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.providers.runtime")
public record ProviderRuntimeProperties(
        @NotNull Duration cacheTtl,
        @Min(1_024) @Max(20_000_000) int maxCacheEntryBytes
) {
    public ProviderRuntimeProperties {
        if (cacheTtl != null
                && (cacheTtl.isZero() || cacheTtl.isNegative()
                || cacheTtl.compareTo(Duration.ofDays(7)) > 0)) {
            throw new IllegalArgumentException(
                    "Provider cache TTL must be greater than zero and no more than seven days"
            );
        }
    }
}
