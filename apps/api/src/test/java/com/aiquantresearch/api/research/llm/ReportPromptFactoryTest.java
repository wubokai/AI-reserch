package com.aiquantresearch.api.research.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aiquantresearch.api.research.application.CanonicalHashService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ReportPromptFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void rejectsEvidencePackBeforeNetworkWhenUtf8BoundaryIsExceeded() {
        LlmProperties properties = mock(LlmProperties.class);
        when(properties.reportModel()).thenReturn("configured-model");
        when(properties.promptVersion()).thenReturn("report_prompt_v1");
        when(properties.schemaVersion()).thenReturn("research_report_v1");
        when(properties.maxOutputTokens()).thenReturn(2_000);
        when(properties.maxInputBytes()).thenReturn(32);
        ReportPromptFactory factory = new ReportPromptFactory(
                objectMapper,
                new CanonicalHashService(objectMapper),
                new LlmSchemaCatalog(objectMapper),
                new OpenAiSchemaNormalizer(),
                properties
        );

        assertThatThrownBy(() -> factory.prepare(
                LlmTestFixtures.request(objectMapper),
                objectMapper.createObjectNode().put("schemaVersion", "research_report_v1")
        )).isInstanceOfSatisfying(OpenAiResponseException.class,
                exception -> assertThat(exception.code()).isEqualTo("LLM_INPUT_TOO_LARGE"));
    }
}
