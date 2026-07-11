package com.aiquantresearch.api.research.llm;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class LlmPropertiesSecurityTest {

    @Test
    void acceptsOnlyOfficialHttpsAndLoopbackTestEndpoints() {
        assertThatNoException().isThrownBy(() ->
                LlmProperties.validateBaseUrl(URI.create("https://api.openai.com")));
        assertThatNoException().isThrownBy(() ->
                LlmProperties.validateBaseUrl(URI.create("http://127.0.0.1:8089")));
    }

    @Test
    void rejectsPrivateMetadataUserInfoAndLookalikeEndpoints() {
        assertThatThrownBy(() -> LlmProperties.validateBaseUrl(
                URI.create("http://169.254.169.254/latest/meta-data")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LlmProperties.validateBaseUrl(
                URI.create("https://token@api.openai.com")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LlmProperties.validateBaseUrl(
                URI.create("https://api.openai.com.attacker.invalid")
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
