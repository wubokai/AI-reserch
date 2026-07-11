package com.aiquantresearch.api.shared.config;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiquantresearch.api.shared.domain.DataMode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RuntimeBoundaryValidatorTest {

    @Test
    void mixedTestModeRequiresTestProfile() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("development");
        var validator = new RuntimeBoundaryValidator(
                properties(DataMode.MIXED_TEST),
                environment
        );

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test profile");
    }

    @Test
    void productionRequiresRealMode() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        var validator = new RuntimeBoundaryValidator(properties(DataMode.MOCK), environment);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REAL data mode");
    }

    @Test
    void mockModeIsAllowedInDevelopment() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("development");
        var validator = new RuntimeBoundaryValidator(properties(DataMode.MOCK), environment);

        assertThatNoException().isThrownBy(validator::validate);
    }

    @Test
    void mockModeRejectsRealFilingProvider() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("development");
        environment.setProperty("app.providers.filing", "sec");
        var validator = new RuntimeBoundaryValidator(properties(DataMode.MOCK), environment);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("real filing provider");
    }

    private ApplicationProperties properties(DataMode dataMode) {
        return new ApplicationProperties("ai-quant-research-api", "test", dataMode);
    }
}
