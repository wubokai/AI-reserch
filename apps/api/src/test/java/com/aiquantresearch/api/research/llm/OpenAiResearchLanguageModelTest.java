package com.aiquantresearch.api.research.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aiquantresearch.api.research.application.CanonicalHashService;
import com.aiquantresearch.api.research.report.DeterministicMockReportGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class OpenAiResearchLanguageModelTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final List<String> requestBodies = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsStatelessStrictResponseRequestAndAuditsUsage() throws Exception {
        start(exchange -> {
            requestBodies.add(readBody(exchange));
            assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                    .isEqualTo("Bearer test-api-key");
            respond(exchange, 200, completed("resp_1", reportJson(), 100, 20, 25));
        });

        var fixture = client();
        ResearchLanguageModelResult result = fixture.model().generateReport(
                LlmTestFixtures.request(objectMapper)
        );

        assertThat(result.report().path("schemaVersion").asText())
                .isEqualTo("research_report_v1");
        assertThat(result.audit().provider()).isEqualTo("OPENAI");
        assertThat(result.audit().providerRequestId()).isEqualTo("resp_1");
        assertThat(result.audit().usage()).isEqualTo(new LlmUsage(100, 20, 25));
        assertThat(result.audit().estimatedCostUsd()).isEqualByComparingTo("0.00015750");
        assertThat(result.audit().budgetReservationId()).isEqualTo(fixture.reservationId());
        assertThat(result.audit().networkCallCount()).isEqualTo(1);

        JsonNode request = objectMapper.readTree(requestBodies.getFirst());
        assertThat(request.path("store").asBoolean()).isFalse();
        assertThat(request.path("parallel_tool_calls").asBoolean()).isFalse();
        assertThat(request.path("text").path("format").path("type").asText())
                .isEqualTo("json_schema");
        assertThat(request.path("text").path("format").path("strict").asBoolean()).isTrue();
        assertThat(request.path("text").path("format").path("schema")
                .path("additionalProperties").asBoolean()).isFalse();
        assertThat(request.path("tools").size()).isEqualTo(3);
        request.path("tools").forEach(tool -> {
            assertThat(tool.path("strict").asBoolean()).isTrue();
            assertThat(tool.path("parameters").path("additionalProperties").asBoolean()).isFalse();
        });
        assertThat(request.path("safety_identifier").asText())
                .startsWith("usr_")
                .doesNotContain("22222222-2222-4222-8222-222222222222");
        assertThat(request.path("instructions").asText())
                .doesNotContain("transfer_funds");
        assertThat(request.path("input").toString()).contains("UNTRUSTED_EXTERNAL_DATA");
    }

    @Test
    void executesOnlyAllowlistedToolAndReturnsResultInNextRound() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> {
            requestBodies.add(readBody(exchange));
            if (calls.getAndIncrement() == 0) {
                respond(exchange, 200, """
                        {"id":"resp_tool","status":"completed","output":[{
                          "type":"function_call","call_id":"call_1",
                          "name":"get_evidence",
                          "arguments":"{\\\"evidenceId\\\":\\\"ev_allowed_1\\\"}"
                        }],"usage":{"input_tokens":50,"output_tokens":5,
                          "input_tokens_details":{"cached_tokens":0}}}
                        """);
            } else {
                respond(exchange, 200, completed("resp_final", reportJson(), 60, 10, 10));
            }
        });

        ResearchLanguageModelResult result = client().model().generateReport(
                LlmTestFixtures.request(objectMapper)
        );

        assertThat(result.audit().usage()).isEqualTo(new LlmUsage(110, 15, 10));
        assertThat(result.audit().networkCallCount()).isEqualTo(2);
        assertThat(requestBodies).hasSize(2);
        JsonNode second = objectMapper.readTree(requestBodies.get(1));
        assertThat(second.path("input").toString()).contains("function_call_output");
        assertThat(second.path("input").toString()).contains("ev_allowed_1");
        assertThat(second.path("input").toString()).doesNotContain("transfer_funds\":{");
    }

    @Test
    void classifiesRateLimitAndReleasesReservation() throws Exception {
        start(exchange -> respond(exchange, 429, "{}"));
        var fixture = client();

        assertThatThrownBy(() -> fixture.model().generateReport(
                LlmTestFixtures.request(objectMapper)
        )).isInstanceOfSatisfying(OpenAiResponseException.class, exception -> {
            assertThat(exception.code()).isEqualTo("LLM_HTTP_429");
            assertThat(exception.retryable()).isTrue();
        });
        verify(fixture.budgetService()).release(fixture.reservationId());
        verify(fixture.failureAuditService()).record(
                any(), any(), any(), any(), anyLong(), any(), any(), anyInt()
        );
        var order = inOrder(fixture.failureAuditService(), fixture.budgetService());
        order.verify(fixture.failureAuditService()).record(
                any(), any(), any(), any(), anyLong(), any(), any(), anyInt()
        );
        order.verify(fixture.budgetService()).release(fixture.reservationId());
    }

    @Test
    void rejectsInvalidStructuredOutputWithoutRetry() throws Exception {
        start(exchange -> respond(exchange, 200, completed(
                "resp_bad",
                "{\"schemaVersion\":\"wrong\"}",
                10,
                2,
                0
        )));
        var fixture = client();

        assertThatThrownBy(() -> fixture.model().generateReport(
                LlmTestFixtures.request(objectMapper)
        )).isInstanceOfSatisfying(OpenAiResponseException.class, exception -> {
            assertThat(exception.code()).isEqualTo("LLM_SCHEMA_INVALID");
            assertThat(exception.retryable()).isFalse();
        });
        verify(fixture.budgetService()).release(fixture.reservationId());
        verify(fixture.failureAuditService()).record(
                any(), any(), any(), any(), anyLong(), any(), any(), anyInt()
        );
    }

    private ClientFixture client() {
        URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        LlmProperties properties = LlmTestFixtures.properties(baseUrl);
        CanonicalHashService hashService = new CanonicalHashService(objectMapper);
        DeterministicMockReportGenerator baseline = mock(DeterministicMockReportGenerator.class);
        when(baseline.generate(any(), anyList(), anyList(), anyList(), anyInt()))
                .thenReturn(objectMapper.createObjectNode()
                        .put("schemaVersion", "research_report_v1"));
        LlmSchemaCatalog catalog = new LlmSchemaCatalog(objectMapper);
        ReportPromptFactory promptFactory = new ReportPromptFactory(
                objectMapper,
                hashService,
                catalog,
                new OpenAiSchemaNormalizer(),
                properties
        );
        LlmBudgetService budgetService = mock(LlmBudgetService.class);
        LlmFailureAuditService failureAuditService = mock(LlmFailureAuditService.class);
        UUID reservationId = UUID.fromString("44444444-4444-4444-8444-444444444444");
        when(budgetService.reserve(any(), any(), any(), any(), anyInt()))
                .thenReturn(new LlmBudgetReservation(
                        reservationId,
                        java.math.BigDecimal.ONE,
                        3
                ));
        OpenAiResearchLanguageModel model = new OpenAiResearchLanguageModel(
                WebClient.builder(),
                objectMapper,
                properties,
                baseline,
                promptFactory,
                new LlmToolExecutor(objectMapper, hashService),
                new LlmPricingPolicy(properties),
                budgetService,
                failureAuditService,
                hashService
        );
        return new ClientFixture(model, budgetService, failureAuditService, reservationId);
    }

    private void start(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> handler.handle(exchange));
        server.start();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
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

    private static String completed(
            String id,
            String outputText,
            int inputTokens,
            int outputTokens,
            int cachedTokens
    ) {
        return """
                {"id":"%s","status":"completed","output":[{
                  "type":"message","content":[{"type":"output_text","text":%s}]
                }],"usage":{"input_tokens":%d,"output_tokens":%d,
                  "input_tokens_details":{"cached_tokens":%d}}}
                """.formatted(
                id,
                quote(outputText),
                inputTokens,
                outputTokens,
                cachedTokens
        );
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String reportJson() {
        return "{\"schemaVersion\":\"research_report_v1\"}";
    }

    private record ClientFixture(
            OpenAiResearchLanguageModel model,
            LlmBudgetService budgetService,
            LlmFailureAuditService failureAuditService,
            UUID reservationId
    ) {
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
