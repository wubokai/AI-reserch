package com.aiquantresearch.api.research.llm;

import com.aiquantresearch.api.research.application.CanonicalHashService;
import com.aiquantresearch.api.research.report.DeterministicMockReportGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
public class OpenAiResearchLanguageModel implements ResearchLanguageModel {

    private static final String PROVIDER = "OPENAI";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final LlmProperties properties;
    private final DeterministicMockReportGenerator baselineGenerator;
    private final ReportPromptFactory promptFactory;
    private final LlmToolExecutor toolExecutor;
    private final LlmPricingPolicy pricingPolicy;
    private final LlmBudgetService budgetService;
    private final LlmFailureAuditService failureAuditService;
    private final CanonicalHashService hashService;

    public OpenAiResearchLanguageModel(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            LlmProperties properties,
            DeterministicMockReportGenerator baselineGenerator,
            ReportPromptFactory promptFactory,
            LlmToolExecutor toolExecutor,
            LlmPricingPolicy pricingPolicy,
            LlmBudgetService budgetService,
            LlmFailureAuditService failureAuditService,
            CanonicalHashService hashService
    ) {
        this.webClient = builder.baseUrl(properties.baseUrl().toString()).build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.baselineGenerator = baselineGenerator;
        this.promptFactory = promptFactory;
        this.toolExecutor = toolExecutor;
        this.pricingPolicy = pricingPolicy;
        this.budgetService = budgetService;
        this.failureAuditService = failureAuditService;
        this.hashService = hashService;
    }

    @Override
    public ResearchLanguageModelResult generateReport(ResearchLanguageModelRequest request) {
        validateConfiguration();
        JsonNode baseline = baselineGenerator.generate(
                request.context(),
                request.sources(),
                request.quantResults(),
                request.evidence(),
                request.reportVersion()
        );
        PreparedLlmRequest prepared = promptFactory.prepare(request, baseline);
        int maximumNetworkCalls = properties.maxToolRounds() + 1;
        int initialRequestBytes = requestBody(prepared, prepared.input())
                .toString()
                .getBytes(StandardCharsets.UTF_8)
                .length;
        if (initialRequestBytes > properties.maxInputBytes()) {
            throw new OpenAiResponseException(
                    "LLM_INPUT_TOO_LARGE",
                    "The complete LLM request exceeds the configured input boundary",
                    false
            );
        }
        LlmBudgetReservation reservation = budgetService.reserve(
                request.context().researchId(),
                request.attemptId(),
                prepared.requestHash(),
                pricingPolicy.reserveUpperBound(initialRequestBytes, maximumNetworkCalls),
                maximumNetworkCalls
        );
        ArrayNode conversation = prepared.input().deepCopy();
        LlmUsage usage = LlmUsage.empty();
        int networkCallCount = 0;
        String providerRequestId = null;
        long started = System.nanoTime();

        try {
            for (int round = 0; round <= properties.maxToolRounds(); round++) {
                networkCallCount++;
                JsonNode response = send(requestBody(prepared, conversation));
                providerRequestId = nullableText(response, "id");
                usage = usage.plus(usage(response));
                rejectNonCompleted(response);

                List<JsonNode> calls = functionCalls(response);
                if (!calls.isEmpty()) {
                    if (round == properties.maxToolRounds()) {
                        throw new OpenAiResponseException(
                                "LLM_TOOL_ROUND_LIMIT",
                                "The model exceeded the allowed read-only tool rounds",
                                false
                        );
                    }
                    if (calls.size() != 1) {
                        throw new OpenAiResponseException(
                                "LLM_PARALLEL_TOOL_CALL_REJECTED",
                                "The model returned more than one tool call in a constrained round",
                                false
                        );
                    }
                    response.path("output").forEach(item -> conversation.add(item.deepCopy()));
                    JsonNode call = calls.getFirst();
                    JsonNode arguments = parseArguments(call.path("arguments").asText());
                    String toolOutput = toolExecutor.execute(
                            call.path("name").asText(),
                            arguments,
                            request
                    );
                    if (toolOutput.getBytes(StandardCharsets.UTF_8).length
                            > properties.maxToolOutputBytes()) {
                        throw new OpenAiResponseException(
                                "LLM_TOOL_OUTPUT_TOO_LARGE",
                                "The read-only tool result exceeds the configured LLM boundary",
                                false
                        );
                    }
                    ObjectNode output = conversation.addObject();
                    output.put("type", "function_call_output");
                    output.put("call_id", requiredText(call, "call_id"));
                    output.put("output", toolOutput);
                    continue;
                }

                JsonNode report = parseReport(outputText(response));
                String responseHash = hashService.hash(report);
                long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                return new ResearchLanguageModelResult(
                        report,
                        new LlmCallAudit(
                                PROVIDER,
                                properties.reportModel(),
                                properties.promptVersion(),
                                properties.schemaVersion(),
                                prepared.requestHash(),
                                responseHash,
                                usage,
                                pricingPolicy.calculate(usage),
                                latencyMs,
                                "SUCCEEDED",
                                null,
                                false,
                                providerRequestId,
                                properties.pricingVersion(),
                                reservation.id(),
                                networkCallCount
                        )
                );
            }
            throw new IllegalStateException("LLM tool loop terminated unexpectedly");
        } catch (RuntimeException exception) {
            OpenAiResponseException normalized = exception instanceof OpenAiResponseException known
                    ? known
                    : new OpenAiResponseException(
                            "LLM_INTERNAL_ERROR",
                            "The LLM adapter failed before producing a safe structured report",
                            false,
                            exception
                    );
            budgetService.release(reservation.id());
            if (networkCallCount > 0) {
                long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                try {
                    failureAuditService.record(
                            request,
                            prepared,
                            usage,
                            pricingPolicy.calculate(usage),
                            latencyMs,
                            normalized.code(),
                            providerRequestId,
                            networkCallCount
                    );
                } catch (RuntimeException auditFailure) {
                    normalized.addSuppressed(auditFailure);
                }
            }
            throw normalized;
        }
    }

    private ObjectNode requestBody(PreparedLlmRequest prepared, ArrayNode conversation) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.reportModel());
        body.put("store", false);
        body.put("max_output_tokens", properties.maxOutputTokens());
        body.put("parallel_tool_calls", false);
        body.put("safety_identifier", prepared.safetyIdentifier());
        body.put("prompt_cache_key", prepared.promptCacheKey());
        body.put("instructions", prepared.instructions());
        body.set("input", conversation.deepCopy());
        body.set("tools", prepared.tools().deepCopy());
        ObjectNode format = body.putObject("text").putObject("format");
        format.put("type", "json_schema");
        format.put("name", properties.schemaVersion());
        format.put("strict", true);
        format.set("schema", prepared.schema().deepCopy());
        body.putObject("reasoning").put("effort", properties.reasoningEffort());
        ObjectNode metadata = body.putObject("metadata");
        metadata.put("prompt_version", properties.promptVersion());
        metadata.put("schema_version", properties.schemaVersion());
        metadata.put("evidence_pack_hash", prepared.evidencePackHash());
        metadata.put("tool_version", ReportPromptFactory.TOOL_VERSION);
        return body;
    }

    private JsonNode send(JsonNode body) {
        try {
            JsonNode response = webClient.post()
                    .uri("/v1/responses")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(properties.timeout());
            if (response == null || !response.isObject()) {
                throw new OpenAiResponseException(
                        "LLM_RESPONSE_INVALID",
                        "OpenAI returned an empty or invalid response",
                        true
                );
            }
            return response;
        } catch (WebClientResponseException exception) {
            int status = exception.getStatusCode().value();
            boolean retryable = status == 429 || status == 502 || status == 503;
            throw new OpenAiResponseException(
                    "LLM_HTTP_" + status,
                    "OpenAI request failed with HTTP " + status,
                    retryable,
                    exception
            );
        } catch (WebClientRequestException exception) {
            throw new OpenAiResponseException(
                    "LLM_UNAVAILABLE",
                    "OpenAI is temporarily unavailable",
                    true,
                    exception
            );
        } catch (IllegalStateException exception) {
            throw new OpenAiResponseException(
                    "LLM_TIMEOUT",
                    "OpenAI request exceeded the configured timeout",
                    true,
                    exception
            );
        }
    }

    private void rejectNonCompleted(JsonNode response) {
        if (response.path("error").isObject()) {
            throw new OpenAiResponseException(
                    "LLM_RESPONSE_ERROR",
                    "OpenAI returned a response error",
                    false
            );
        }
        String status = response.path("status").asText();
        if ("incomplete".equals(status)) {
            String reason = response.path("incomplete_details").path("reason").asText("unknown");
            throw new OpenAiResponseException(
                    "LLM_INCOMPLETE_" + safeCode(reason),
                    "OpenAI returned an incomplete structured response",
                    false
            );
        }
        if (!"completed".equals(status)) {
            throw new OpenAiResponseException(
                    "LLM_RESPONSE_STATUS_INVALID",
                    "OpenAI returned an unsupported response status",
                    false
            );
        }
    }

    private List<JsonNode> functionCalls(JsonNode response) {
        List<JsonNode> calls = new ArrayList<>();
        response.path("output").forEach(item -> {
            if ("function_call".equals(item.path("type").asText())) {
                calls.add(item);
            }
        });
        return List.copyOf(calls);
    }

    private String outputText(JsonNode response) {
        String aggregate = response.path("output_text").asText();
        if (!aggregate.isBlank()) {
            return aggregate;
        }
        for (JsonNode item : response.path("output")) {
            for (JsonNode content : item.path("content")) {
                if ("refusal".equals(content.path("type").asText())) {
                    throw new OpenAiResponseException(
                            "LLM_REFUSED",
                            "OpenAI refused to generate the structured report",
                            false
                    );
                }
                if ("output_text".equals(content.path("type").asText())) {
                    String text = content.path("text").asText();
                    if (!text.isBlank()) {
                        return text;
                    }
                }
            }
        }
        throw new OpenAiResponseException(
                "LLM_OUTPUT_MISSING",
                "OpenAI completed without a structured report",
                false
        );
    }

    private JsonNode parseReport(String value) {
        try {
            JsonNode report = objectMapper.readTree(value);
            if (!report.isObject()
                    || !properties.schemaVersion().equals(report.path("schemaVersion").asText())) {
                throw new OpenAiResponseException(
                        "LLM_SCHEMA_INVALID",
                        "OpenAI output did not match the configured report schema version",
                        false
                );
            }
            return report;
        } catch (JsonProcessingException exception) {
            throw new OpenAiResponseException(
                    "LLM_SCHEMA_INVALID",
                    "OpenAI output was not valid structured JSON",
                    false,
                    exception
            );
        }
    }

    private JsonNode parseArguments(String value) {
        try {
            JsonNode arguments = objectMapper.readTree(value);
            if (!arguments.isObject()) {
                throw new OpenAiResponseException(
                        "LLM_TOOL_ARGUMENTS_INVALID",
                        "OpenAI returned invalid tool arguments",
                        false
                );
            }
            return arguments;
        } catch (JsonProcessingException exception) {
            throw new OpenAiResponseException(
                    "LLM_TOOL_ARGUMENTS_INVALID",
                    "OpenAI returned invalid tool arguments",
                    false,
                    exception
            );
        }
    }

    private static LlmUsage usage(JsonNode response) {
        JsonNode usage = response.path("usage");
        return new LlmUsage(
                usage.path("input_tokens").asInt(0),
                usage.path("output_tokens").asInt(0),
                usage.path("input_tokens_details").path("cached_tokens").asInt(0)
        );
    }

    private void validateConfiguration() {
        if (properties.apiKey() == null || properties.reportModel() == null) {
            throw new OpenAiResponseException(
                    "LLM_CONFIGURATION_INVALID",
                    "Real LLM mode requires both API key and report model",
                    false
            );
        }
        if (!properties.hasVersionedPricing()) {
            throw new OpenAiResponseException(
                    "LLM_PRICING_UNKNOWN",
                    "Real LLM mode requires versioned pricing before reserving budget",
                    false
            );
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new OpenAiResponseException(
                    "LLM_RESPONSE_INVALID",
                    "OpenAI returned an invalid tool call",
                    false
            );
        }
        return value;
    }

    private static String nullableText(JsonNode node, String field) {
        String value = node.path(field).asText();
        return value.isBlank() ? null : value;
    }

    private static String safeCode(String value) {
        return value.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }
}
