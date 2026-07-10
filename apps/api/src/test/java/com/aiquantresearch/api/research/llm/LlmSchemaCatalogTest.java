package com.aiquantresearch.api.research.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LlmSchemaCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void loadsEveryVersionedStrictSchema() {
        LlmSchemaCatalog catalog = new LlmSchemaCatalog(objectMapper);

        for (LlmTaskType taskType : LlmTaskType.values()) {
            var schema = catalog.schema(taskType);
            assertThat(schema.path("type").asText()).isEqualTo("object");
            assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
            assertThat(schema.path("required").isArray()).isTrue();
        }
    }

    @Test
    void createsOpenAiSubsetWithoutWeakeningObjectClosure() {
        var normalized = new OpenAiSchemaNormalizer().normalize(
                new LlmSchemaCatalog(objectMapper).schema(LlmTaskType.FINAL_REPORT)
        );

        assertThat(normalized.has("$schema")).isFalse();
        assertThat(normalized.has("$id")).isFalse();
        assertThat(normalized.path("additionalProperties").asBoolean()).isFalse();
        assertThat(normalized.path("properties").path("asOfDate").has("format")).isFalse();
        assertThat(normalized.path("properties").path("sections").has("maxItems")).isFalse();
    }
}
