package com.aiquantresearch.api.research.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ResearchLanguageModelRouterTest {

    @Test
    void explicitlyFallsBackToSafeDeterministicReportAfterRealFailure() {
        LlmProperties properties = LlmTestFixtures.properties(
                URI.create("https://api.openai.test")
        );
        MockResearchLanguageModel mockModel = mock(MockResearchLanguageModel.class);
        OpenAiResearchLanguageModel openAiModel = mock(OpenAiResearchLanguageModel.class);
        ResearchLanguageModelRequest request = LlmTestFixtures.request(new ObjectMapper());
        ResearchLanguageModelResult expected = mock(ResearchLanguageModelResult.class);
        when(openAiModel.generateReport(request)).thenThrow(new OpenAiResponseException(
                "LLM_HTTP_503",
                "temporarily unavailable",
                true
        ));
        when(mockModel.safeFallback(request, "LLM_HTTP_503")).thenReturn(expected);

        ResearchLanguageModelResult actual = new ResearchLanguageModelRouter(
                properties,
                mockModel,
                openAiModel
        ).generateReport(request);

        assertThat(actual).isSameAs(expected);
        verify(mockModel).safeFallback(request, "LLM_HTTP_503");
    }
}
