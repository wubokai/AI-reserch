package com.aiquantresearch.api.research.llm;

public record LlmUsage(
        int inputTokens,
        int outputTokens,
        int cachedInputTokens
) {

    public LlmUsage {
        if (inputTokens < 0 || outputTokens < 0 || cachedInputTokens < 0) {
            throw new IllegalArgumentException("LLM token usage cannot be negative");
        }
        if (cachedInputTokens > inputTokens) {
            throw new IllegalArgumentException("Cached input tokens cannot exceed input tokens");
        }
    }

    public LlmUsage plus(LlmUsage other) {
        return new LlmUsage(
                Math.addExact(inputTokens, other.inputTokens),
                Math.addExact(outputTokens, other.outputTokens),
                Math.addExact(cachedInputTokens, other.cachedInputTokens)
        );
    }

    public static LlmUsage empty() {
        return new LlmUsage(0, 0, 0);
    }
}
