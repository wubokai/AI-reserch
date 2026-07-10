package com.aiquantresearch.api.research.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class HttpAnalyticsClientTest {

    private static final String INPUT_HASH = "a".repeat(64);

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void classifiesServiceUnavailableAsRetryable() throws Exception {
        start(exchange -> respond(exchange, 503, "{}"));

        assertThatThrownBy(() -> client(Duration.ofSeconds(1)).runFullAnalysis(request()))
                .isInstanceOfSatisfying(AnalyticsServiceException.class, exception -> {
                    assertThat(exception.isRetryable()).isTrue();
                    assertThat(exception).hasMessageContaining("HTTP 503");
                });
    }

    @Test
    void rejectsAnalyticsContractDriftWithoutRetrying() throws Exception {
        start(exchange -> respond(exchange, 200, """
                {"schemaVersion":"unknown","calculationVersion":"quant_v1",\
                 "inputHash":"%s"}
                """.formatted(INPUT_HASH)));

        assertThatThrownBy(() -> client(Duration.ofSeconds(1)).runFullAnalysis(request()))
                .isInstanceOfSatisfying(AnalyticsServiceException.class, exception -> {
                    assertThat(exception.isRetryable()).isFalse();
                    assertThat(exception).hasMessageContaining("schemaVersion");
                });
    }

    @Test
    void acceptsVersionedPhase4MetricsAndExplainableTrend() throws Exception {
        start(exchange -> respond(exchange, 200, """
                {"schemaVersion":"analytics_full_response_v1",\
                 "calculationVersion":"quant_v1",\
                 "inputHash":"%s",\
                 "metrics":[{"name":"total_return","value":"0.1",\
                   "status":"AVAILABLE","sampleSize":100,\
                   "calculationVersion":"quant_v1","warnings":[]}],\
                 "trend":{"classification":"UPTREND","score":3,"signals":{},\
                   "calculationVersion":"quant_v1"}}
                """.formatted(INPUT_HASH)));

        assertThat(client(Duration.ofSeconds(1)).runFullAnalysis(request())
                .path("trend").path("classification").asText()).isEqualTo("UPTREND");
    }

    @Test
    void rejectsMetricVersionDriftWithoutRetrying() throws Exception {
        start(exchange -> respond(exchange, 200, """
                {"schemaVersion":"analytics_full_response_v1",\
                 "calculationVersion":"quant_v1",\
                 "inputHash":"%s",\
                 "metrics":[{"name":"total_return","status":"AVAILABLE",\
                   "sampleSize":100,"calculationVersion":"quant_v2","warnings":[]}],\
                 "trend":null}
                """.formatted(INPUT_HASH)));

        assertThatThrownBy(() -> client(Duration.ofSeconds(1)).runFullAnalysis(request()))
                .isInstanceOfSatisfying(AnalyticsServiceException.class, exception -> {
                    assertThat(exception.isRetryable()).isFalse();
                    assertThat(exception).hasMessageContaining("versioned metric");
                });
    }

    @Test
    void classifiesResponseTimeoutAsRetryable() throws Exception {
        start(exchange -> {
            try {
                Thread.sleep(250);
                respond(exchange, 200, "{}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        assertThatThrownBy(() -> client(Duration.ofMillis(25)).runFullAnalysis(request()))
                .isInstanceOfSatisfying(AnalyticsServiceException.class, exception -> {
                    assertThat(exception.isRetryable()).isTrue();
                    assertThat(exception).hasMessageContaining("timeout");
                });
    }

    private void start(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/analytics/v1/full-analysis", exchange -> handler.handle(exchange));
        server.start();
    }

    private HttpAnalyticsClient client(Duration timeout) {
        URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new HttpAnalyticsClient(
                WebClient.builder(),
                new AnalyticsProperties(baseUrl, timeout)
        );
    }

    private static com.fasterxml.jackson.databind.JsonNode request() {
        return JsonNodeFactory.instance.objectNode().put("inputHash", INPUT_HASH);
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, content.length);
        try (var output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
