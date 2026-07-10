package com.aiquantresearch.api.research.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class OpenAiSchemaNormalizer {

    private static final Set<String> DOMAIN_ONLY_KEYWORDS = Set.of(
            "$schema", "$id", "title", "format", "pattern",
            "minLength", "maxLength", "minimum", "maximum",
            "minItems", "maxItems", "uniqueItems", "contains",
            "minContains", "maxContains", "allOf"
    );

    public ObjectNode normalize(JsonNode canonicalSchema) {
        if (canonicalSchema == null || !canonicalSchema.isObject()) {
            throw new IllegalArgumentException("Canonical LLM schema must be an object");
        }
        ObjectNode normalized = canonicalSchema.deepCopy();
        sanitize(normalized);
        return normalized;
    }

    private void sanitize(JsonNode node) {
        if (node instanceof ObjectNode object) {
            DOMAIN_ONLY_KEYWORDS.forEach(object::remove);
            object.elements().forEachRemaining(this::sanitize);
        } else if (node instanceof ArrayNode array) {
            array.elements().forEachRemaining(this::sanitize);
        }
    }
}
