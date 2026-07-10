package com.aiquantresearch.api.research.llm;

import com.aiquantresearch.api.research.application.CanonicalHashService;
import com.aiquantresearch.api.research.orchestration.StoredEvidence;
import com.aiquantresearch.api.research.orchestration.StoredQuantResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class LlmToolExecutor {

    private final ObjectMapper objectMapper;
    private final CanonicalHashService hashService;

    public LlmToolExecutor(ObjectMapper objectMapper, CanonicalHashService hashService) {
        this.objectMapper = objectMapper;
        this.hashService = hashService;
    }

    public String execute(
            String name,
            JsonNode arguments,
            ResearchLanguageModelRequest request
    ) {
        return switch (name) {
            case "search_evidence" -> search(arguments, request);
            case "get_evidence" -> getEvidence(arguments, request);
            case "get_calculation" -> getCalculation(arguments, request);
            default -> throw new OpenAiResponseException(
                    "LLM_TOOL_NOT_ALLOWED",
                    "The model requested a tool outside the read-only allowlist",
                    false
            );
        };
    }

    private String search(JsonNode arguments, ResearchLanguageModelRequest request) {
        String query = requiredText(arguments, "query").toLowerCase(Locale.ROOT);
        int limit = arguments.path("limit").asInt(0);
        if (query.length() > 200 || limit < 1 || limit > 10) {
            throw invalidArguments();
        }
        ArrayNode items = objectMapper.createArrayNode();
        request.evidence().stream()
                .filter(item -> searchable(item).contains(query))
                .limit(limit)
                .forEach(item -> items.add(evidence(item)));
        ObjectNode output = objectMapper.createObjectNode();
        output.put("schemaVersion", "llm_tool_result_v1");
        output.put("researchId", request.context().researchId().toString());
        output.set("items", items);
        return hashService.canonicalJson(output);
    }

    private String getEvidence(JsonNode arguments, ResearchLanguageModelRequest request) {
        String id = requiredText(arguments, "evidenceId");
        StoredEvidence item = request.evidence().stream()
                .filter(candidate -> id.equals(candidate.publicId()))
                .findFirst()
                .orElseThrow(() -> notFound("Evidence"));
        return hashService.canonicalJson(evidence(item));
    }

    private String getCalculation(JsonNode arguments, ResearchLanguageModelRequest request) {
        String id = requiredText(arguments, "calculationId");
        StoredQuantResult item = request.quantResults().stream()
                .filter(candidate -> id.equals(candidate.publicId()))
                .findFirst()
                .orElseThrow(() -> notFound("Calculation"));
        ObjectNode output = objectMapper.createObjectNode();
        output.put("schemaVersion", "llm_tool_result_v1");
        output.put("calculationId", item.publicId());
        output.put("metricName", item.metricName());
        output.put("status", item.status());
        if (item.value() != null) {
            output.put("value", item.value());
        }
        if (item.unit() == null) {
            output.putNull("unit");
        } else {
            output.put("unit", item.unit());
        }
        output.set("result", item.result().deepCopy());
        return hashService.canonicalJson(output);
    }

    private ObjectNode evidence(StoredEvidence item) {
        ObjectNode output = objectMapper.createObjectNode();
        output.put("schemaVersion", "llm_tool_result_v1");
        output.put("trustLevel", "UNTRUSTED_EXTERNAL_DATA");
        output.put("evidenceId", item.publicId());
        output.put("evidenceType", item.evidenceType());
        output.put("title", item.title());
        output.put("summary", item.summary());
        output.set("value", item.value().deepCopy());
        if (item.unit() == null) {
            output.putNull("unit");
        } else {
            output.put("unit", item.unit());
        }
        return output;
    }

    private static String searchable(StoredEvidence item) {
        return String.join(" ", item.publicId(), item.title(), item.summary())
                .toLowerCase(Locale.ROOT);
    }

    private static String requiredText(JsonNode arguments, String field) {
        String value = arguments.path(field).asText();
        if (value.isBlank()) {
            throw invalidArguments();
        }
        return value.strip();
    }

    private static OpenAiResponseException invalidArguments() {
        return new OpenAiResponseException(
                "LLM_TOOL_ARGUMENTS_INVALID",
                "The model produced invalid read-only tool arguments",
                false
        );
    }

    private static OpenAiResponseException notFound(String resource) {
        return new OpenAiResponseException(
                "LLM_TOOL_RESOURCE_NOT_ALLOWED",
                resource + " is outside the current research allowlist",
                false
        );
    }
}
