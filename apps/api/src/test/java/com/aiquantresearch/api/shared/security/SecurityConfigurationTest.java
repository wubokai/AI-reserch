package com.aiquantresearch.api.shared.security;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SecurityConfigurationTest {

    @Test
    void rejectsDemoPrincipalInProduction() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        var properties = new DemoPrincipalProperties(
                true,
                "demo-user",
                "test-only-password"
        );

        assertThatThrownBy(() -> SecurityConfiguration.validateDemoBoundary(properties, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("development and test");
    }

    @Test
    void rejectsImplicitOrWeakDemoCredentials() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("development");
        var properties = new DemoPrincipalProperties(true, "demo-user", "change-me");

        assertThatThrownBy(() -> SecurityConfiguration.validateDemoBoundary(properties, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 16 characters");
    }

    @Test
    void permitsExplicitDemoPrincipalOnlyInDevelopment() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("development");
        var properties = new DemoPrincipalProperties(
                true,
                "demo-user",
                "test-only-password"
        );

        assertThatNoException()
                .isThrownBy(() -> SecurityConfiguration.validateDemoBoundary(properties, environment));
    }
}
