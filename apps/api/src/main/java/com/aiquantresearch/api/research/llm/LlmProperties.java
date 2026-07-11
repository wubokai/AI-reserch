package com.aiquantresearch.api.research.llm;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(
        @NotNull URI baseUrl,
        @NotNull Duration timeout,
        String apiKey,
        String reportModel,
        String validationModel,
        @NotBlank String reasoningEffort,
        @Min(256) @Max(128_000) int maxOutputTokens,
        @Min(1_024) @Max(10_000_000) int maxInputBytes,
        @Min(1_024) @Max(1_000_000) int maxToolOutputBytes,
        @Min(0) @Max(8) int maxToolRounds,
        @NotNull Duration reservationTtl,
        boolean allowSafeFallback,
        String safetyHmacSecret,
        @NotBlank String promptVersion,
        @NotBlank String schemaVersion,
        @NotNull @DecimalMin("0.00") BigDecimal maxCostUsd,
        @Min(0) @Max(100) int maxCalls,
        String pricingVersion,
        String pricingEffectiveFrom,
        String inputUsdPerMillionTokens,
        String cachedInputUsdPerMillionTokens,
        String outputUsdPerMillionTokens
) {

    public LlmProperties {
        validateBaseUrl(baseUrl);
        apiKey = normalize(apiKey);
        reportModel = normalize(reportModel);
        validationModel = normalize(validationModel);
        safetyHmacSecret = normalize(safetyHmacSecret);
        pricingVersion = normalize(pricingVersion);
        pricingEffectiveFrom = normalize(pricingEffectiveFrom);
        inputUsdPerMillionTokens = normalize(inputUsdPerMillionTokens);
        cachedInputUsdPerMillionTokens = normalize(cachedInputUsdPerMillionTokens);
        outputUsdPerMillionTokens = normalize(outputUsdPerMillionTokens);
    }

    static void validateBaseUrl(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("OpenAI base URL is outside the approved boundary");
        }
        String host = uri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
        boolean official = "api.openai.com".equalsIgnoreCase(host)
                && "https".equalsIgnoreCase(uri.getScheme())
                && (uri.getPort() == -1 || uri.getPort() == 443);
        boolean approvedLanYi = "lanyiapi.com".equalsIgnoreCase(host)
                && "https".equalsIgnoreCase(uri.getScheme())
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && approvedApiPath(uri.getPath());
        if (!loopback && !official && !approvedLanYi) {
            throw new IllegalArgumentException("OpenAI base URL is outside the approved boundary");
        }
        if (loopback && !"http".equalsIgnoreCase(uri.getScheme())
                && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("OpenAI loopback test URL uses an unsupported scheme");
        }
    }

    public String providerName() {
        return "lanyiapi.com".equalsIgnoreCase(baseUrl.getHost()) ? "LANYI" : "OPENAI";
    }

    private static boolean approvedApiPath(String path) {
        return path == null || path.isBlank() || "/".equals(path)
                || "/v1".equals(path) || "/v1/".equals(path);
    }

    public boolean mockMode() {
        return apiKey == null && reportModel == null;
    }

    public boolean partiallyConfigured() {
        return (apiKey == null) != (reportModel == null);
    }

    public boolean hasVersionedPricing() {
        return pricingVersion != null
                && pricingEffectiveFrom != null
                && parsePrice(inputUsdPerMillionTokens) != null
                && parsePrice(cachedInputUsdPerMillionTokens) != null
                && parsePrice(outputUsdPerMillionTokens) != null;
    }

    public BigDecimal inputPrice() {
        return parsePrice(inputUsdPerMillionTokens);
    }

    public BigDecimal cachedInputPrice() {
        return parsePrice(cachedInputUsdPerMillionTokens);
    }

    public BigDecimal outputPrice() {
        return parsePrice(outputUsdPerMillionTokens);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private static BigDecimal parsePrice(String value) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal parsed = new BigDecimal(value);
            return parsed.signum() < 0 ? null : parsed;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
