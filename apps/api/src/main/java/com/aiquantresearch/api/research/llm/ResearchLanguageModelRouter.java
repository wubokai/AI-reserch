package com.aiquantresearch.api.research.llm;

import org.springframework.stereotype.Component;

@Component
public class ResearchLanguageModelRouter implements ResearchLanguageModel {

    private final LlmProperties properties;
    private final MockResearchLanguageModel mockModel;
    private final OpenAiResearchLanguageModel openAiModel;

    public ResearchLanguageModelRouter(
            LlmProperties properties,
            MockResearchLanguageModel mockModel,
            OpenAiResearchLanguageModel openAiModel
    ) {
        this.properties = properties;
        this.mockModel = mockModel;
        this.openAiModel = openAiModel;
    }

    @Override
    public ResearchLanguageModelResult generateReport(ResearchLanguageModelRequest request) {
        if (properties.partiallyConfigured()) {
            throw new OpenAiResponseException(
                    "LLM_CONFIGURATION_INVALID",
                    "OPENAI_API_KEY and OPENAI_REPORT_MODEL must be configured together",
                    false
            );
        }
        if (properties.mockMode()) {
            return mockModel.generateReport(request);
        }
        try {
            return openAiModel.generateReport(request);
        } catch (OpenAiResponseException exception) {
            if (!properties.allowSafeFallback()
                    || "LLM_CONFIGURATION_INVALID".equals(exception.code())
                    || "LLM_PRICING_UNKNOWN".equals(exception.code())) {
                throw exception;
            }
            return mockModel.safeFallback(request, exception.code());
        }
    }
}
