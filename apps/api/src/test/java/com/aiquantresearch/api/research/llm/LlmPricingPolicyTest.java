package com.aiquantresearch.api.research.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class LlmPricingPolicyTest {

    @Test
    void calculatesVersionedCachedAndUncachedTokenCost() {
        LlmPricingPolicy policy = new LlmPricingPolicy(
                LlmTestFixtures.properties(URI.create("https://api.openai.test"))
        );

        assertThat(policy.calculate(new LlmUsage(1_000, 500, 250)))
                .isEqualByComparingTo("0.00277500");
        assertThat(policy.reserveUpperBound(4_000, 3))
                .isEqualByComparingTo("0.14030400");
    }
}
