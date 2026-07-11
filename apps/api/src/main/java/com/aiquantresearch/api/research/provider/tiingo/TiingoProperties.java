package com.aiquantresearch.api.research.provider.tiingo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.providers.tiingo")
public record TiingoProperties(
        @NotNull URI baseUrl,
        @NotNull Duration timeout,
        String apiKey,
        String userAgent,
        @Min(1_024) @Max(20_000_000) int maxResponseBytes,
        @Min(1) @Max(4) int maxAttempts
) {

    public TiingoProperties {
        apiKey = normalize(apiKey);
        userAgent = normalize(userAgent);
    }

    public void requireConfiguredAccess() {
        if (apiKey == null || apiKey.length() < 16 || apiKey.length() > 512
                || !apiKey.matches("^[A-Za-z0-9._~-]+$")) {
            throw new IllegalStateException("TIINGO_API_KEY must be a valid API token");
        }
        if (userAgent == null || userAgent.length() > 255) {
            throw new IllegalStateException("TIINGO_USER_AGENT must identify the application");
        }
        validateEndpoint(baseUrl);
    }

    static void validateEndpoint(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException("Tiingo endpoint is outside the approved host boundary");
        }
        String host = uri.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        boolean official = "api.tiingo.com".equalsIgnoreCase(host)
                && "https".equalsIgnoreCase(uri.getScheme())
                && (uri.getPort() == -1 || uri.getPort() == 443);
        if (!loopback && !official) {
            throw new IllegalStateException("Tiingo endpoint is outside the approved host boundary");
        }
        if (loopback && !"http".equalsIgnoreCase(uri.getScheme())
                && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("Tiingo loopback test URL uses an unsupported scheme");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
