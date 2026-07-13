package com.aiquantresearch.api.research.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
public class HttpAnalyticsClient implements AnalyticsClient {

    private final WebClient webClient;
    private final AnalyticsProperties properties;

    public HttpAnalyticsClient(WebClient.Builder builder, AnalyticsProperties properties) {
        this.webClient = builder.baseUrl(properties.baseUrl().toString()).build();
        this.properties = properties;
    }

    @Override
    public JsonNode runFullAnalysis(JsonNode request) {
        return execute(request, "/analytics/v1/full-analysis", "analytics_full_response_v1");
    }

    @Override
    public JsonNode runInsights(JsonNode request) {
        return execute(request, "/analytics/v1/insights", "analytics_insights_response_v1");
    }

    private JsonNode execute(JsonNode request, String path, String responseSchemaVersion) {
        Objects.requireNonNull(request, "request");
        try {
            JsonNode response = webClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(properties.timeout());
            if (response == null || !response.isObject()) {
                throw new AnalyticsServiceException(
                        "Analytics returned an empty or invalid response",
                        true
                );
            }
            String requestHash = request.path("inputHash").asText();
            if (!requestHash.equals(response.path("inputHash").asText())) {
                throw new AnalyticsServiceException(
                        "Analytics response inputHash did not match the request",
                        false
                );
            }
            if (!responseSchemaVersion.equals(response.path("schemaVersion").asText())) {
                throw new AnalyticsServiceException(
                        "Analytics response schemaVersion is unsupported",
                        false
                );
            }
            String expectedCalculationVersion = responseSchemaVersion
                    .equals("analytics_insights_response_v1") ? "insights_v1" : "quant_v1";
            if (!expectedCalculationVersion.equals(
                    response.path("calculationVersion").asText()
            )) {
                throw new AnalyticsServiceException(
                        "Analytics response calculationVersion is unsupported",
                        false
                );
            }
            if (responseSchemaVersion.equals("analytics_insights_response_v1")) {
                validateInsightsShape(response);
            } else {
                validatePhase4Shape(response);
            }
            return response;
        } catch (WebClientResponseException exception) {
            boolean retryable = exception.getStatusCode().is5xxServerError()
                    || exception.getStatusCode().value() == 429;
            throw new AnalyticsServiceException(
                    "Analytics request failed with HTTP " + exception.getStatusCode().value(),
                    retryable,
                    exception
            );
        } catch (WebClientRequestException exception) {
            throw new AnalyticsServiceException(
                    "Analytics service is temporarily unavailable",
                    true,
                    exception
            );
        } catch (IllegalStateException exception) {
            throw new AnalyticsServiceException(
                    "Analytics request exceeded the configured timeout",
                    true,
                    exception
            );
        }
    }

    private static void validateInsightsShape(JsonNode response) {
        if (!response.path("pricePoints").isArray()
                || !response.path("technicalSummary").isObject()
                || !response.path("valuation").isObject()
                || !response.path("valuation").path("sensitivity").path("rows").isArray()) {
            throw new AnalyticsServiceException(
                    "Analytics response is missing insight fields",
                    false
            );
        }
    }

    private static void validatePhase4Shape(JsonNode response) {
        if (!response.path("metrics").isArray() || !response.has("trend")) {
            throw new AnalyticsServiceException(
                    "Analytics response is missing Phase 4 metrics or trend fields",
                    false
            );
        }
        for (JsonNode metric : response.path("metrics")) {
            if (!metric.path("name").isTextual()
                    || !metric.path("status").isTextual()
                    || !metric.path("sampleSize").canConvertToInt()
                    || !"quant_v1".equals(metric.path("calculationVersion").asText())
                    || !metric.path("warnings").isArray()) {
                throw new AnalyticsServiceException(
                        "Analytics response contains an invalid versioned metric",
                        false
                );
            }
        }
        JsonNode trend = response.path("trend");
        if (!trend.isNull()
                && (!trend.path("classification").isTextual()
                || !trend.path("score").canConvertToInt()
                || !trend.path("signals").isObject()
                || !"quant_v1".equals(trend.path("calculationVersion").asText()))) {
            throw new AnalyticsServiceException(
                    "Analytics response contains an invalid trend contract",
                    false
            );
        }
    }
}
