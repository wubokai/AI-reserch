package com.aiquantresearch.api.research.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class LlmPricingPolicy {

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    private final LlmProperties properties;

    public LlmPricingPolicy(LlmProperties properties) {
        this.properties = properties;
    }

    public BigDecimal calculate(LlmUsage usage) {
        if (!properties.hasVersionedPricing()) {
            return null;
        }
        int uncached = usage.inputTokens() - usage.cachedInputTokens();
        BigDecimal cost = properties.inputPrice().multiply(BigDecimal.valueOf(uncached))
                .add(properties.cachedInputPrice()
                        .multiply(BigDecimal.valueOf(usage.cachedInputTokens())))
                .add(properties.outputPrice()
                        .multiply(BigDecimal.valueOf(usage.outputTokens())));
        return cost.divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
    }

    public BigDecimal reserveUpperBound(int inputUtf8Bytes) {
        if (!properties.hasVersionedPricing()) {
            return null;
        }
        BigDecimal cost = properties.inputPrice().multiply(BigDecimal.valueOf(inputUtf8Bytes))
                .add(properties.outputPrice()
                        .multiply(BigDecimal.valueOf(properties.maxOutputTokens())));
        return cost.divide(ONE_MILLION, 8, RoundingMode.UP);
    }
}
