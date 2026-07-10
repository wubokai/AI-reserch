package com.aiquantresearch.api.research.llm;

public interface ResearchLanguageModel {

    ResearchLanguageModelResult generateReport(ResearchLanguageModelRequest request);
}
