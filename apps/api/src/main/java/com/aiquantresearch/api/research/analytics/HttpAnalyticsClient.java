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
        Objects.requireNonNull(request, "request");
        try {
            JsonNode response = webClient.post()
                    .uri("/analytics/v1/full-analysis")
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
            if (!"analytics_full_response_v1".equals(response.path("schemaVersion").asText())) {
                throw new AnalyticsServiceException(
                        "Analytics response schemaVersion is unsupported",
                        false
                );
            }
            if (!"quant_v1".equals(response.path("calculationVersion").asText())) {
                throw new AnalyticsServiceException(
                        "Analytics response calculationVersion is unsupported",
                        false
                );
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
}
