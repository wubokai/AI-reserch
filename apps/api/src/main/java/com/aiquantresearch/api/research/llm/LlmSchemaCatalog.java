package com.aiquantresearch.api.research.llm;

import com.aiquantresearch.api.research.worker.StepExecutionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class LlmSchemaCatalog {

    private final Map<LlmTaskType, JsonNode> schemas;

    public LlmSchemaCatalog(ObjectMapper objectMapper) {
        Map<LlmTaskType, JsonNode> loaded = new EnumMap<>(LlmTaskType.class);
        for (LlmTaskType type : LlmTaskType.values()) {
            loaded.put(type, load(objectMapper, type));
        }
        schemas = Map.copyOf(loaded);
    }

    public JsonNode schema(LlmTaskType type) {
        return schemas.get(type).deepCopy();
    }

    private static JsonNode load(ObjectMapper objectMapper, LlmTaskType type) {
        ClassPathResource resource = new ClassPathResource("llm-schemas/" + type.resourceName());
        try (InputStream stream = resource.getInputStream()) {
            JsonNode schema = objectMapper.readTree(stream);
            if (!schema.isObject()
                    || !"object".equals(schema.path("type").asText())
                    || schema.path("additionalProperties").asBoolean(true)
                    || !schema.path("properties").isObject()) {
                throw new StepExecutionException(
                        "LLM_SCHEMA_INVALID",
                        "A versioned LLM schema is not strict at its root",
                        false
                );
            }
            return schema;
        } catch (IOException exception) {
            throw new StepExecutionException(
                    "LLM_SCHEMA_MISSING",
                    "A required versioned LLM schema is unavailable",
                    false,
                    exception
            );
        }
    }
}
