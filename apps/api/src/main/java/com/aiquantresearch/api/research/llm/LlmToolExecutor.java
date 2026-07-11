package com.aiquantresearch.api.research.llm;

import com.aiquantresearch.api.research.application.CanonicalHashService;
import com.aiquantresearch.api.research.filing.FilingChunkSearchService;
import com.aiquantresearch.api.research.orchestration.StoredEvidence;
import com.aiquantresearch.api.research.orchestration.StoredQuantResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
public class LlmToolExecutor {

    private final ObjectMapper objectMapper;
    private final CanonicalHashService hashService;
    private final FilingChunkSearchService filingSearch;

    public LlmToolExecutor(
            ObjectMapper objectMapper,
            CanonicalHashService hashService,
            FilingChunkSearchService filingSearch
    ) {
        this.objectMapper = objectMapper;
        this.hashService = hashService;
        this.filingSearch = filingSearch;
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
        Set<String> allowedEvidenceIds = request.evidence().stream()
                .map(StoredEvidence::publicId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        try {
            java.util.Optional.ofNullable(filingSearch.search(
                    request.context().researchId(), query, limit * 3
            )).orElseGet(java.util.List::of).stream()
                    .filter(match -> allowedEvidenceIds.contains(match.evidenceId()))
                    .limit(limit)
                    .forEach(match -> items.add(filingChunk(match)));
        } catch (DataAccessException exception) {
            throw new OpenAiResponseException(
                    "LLM_TOOL_SEARCH_UNAVAILABLE",
                    "The research-scoped filing search is temporarily unavailable",
                    true,
                    exception
            );
        }
        request.evidence().stream()
                .filter(item -> searchable(item).contains(query))
                .filter(item -> items.findValuesAsText("evidenceId").stream()
                        .noneMatch(item.publicId()::equals))
                .limit(Math.max(0, limit - items.size()))
                .forEach(item -> items.add(evidence(item)));
        ObjectNode output = objectMapper.createObjectNode();
        output.put("schemaVersion", "llm_tool_result_v1");
        output.put("researchId", request.context().researchId().toString());
        output.set("items", items);
        return hashService.canonicalJson(output);
    }

    private ObjectNode filingChunk(FilingChunkSearchService.FilingChunkMatch match) {
        ObjectNode output = objectMapper.createObjectNode();
        output.put("schemaVersion", "llm_tool_result_v1");
        output.put("trustLevel", "UNTRUSTED_EXTERNAL_DATA");
        output.put("evidenceId", match.evidenceId());
        output.put("evidenceType", "SEC_FILING_CHUNK");
        output.put("externalDocumentId", match.externalDocumentId());
        output.put("formType", match.formType());
        output.put("filingDate", match.filingDate().toString());
        output.put("sectionName", match.sectionName());
        output.put("chunkIndex", match.chunkIndex());
        output.put("citationLocator", match.citationLocator());
        output.put("excerpt", match.excerpt());
        return output;
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
