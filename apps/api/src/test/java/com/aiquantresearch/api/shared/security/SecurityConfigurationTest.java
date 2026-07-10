package com.aiquantresearch.api.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

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

    @Test
    void returnsFreshDemoPrincipalAfterCredentialsAreErased() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("development");
        var properties = new DemoPrincipalProperties(
                true,
                "demo-user",
                "test-only-password"
        );
        var configuration = new SecurityConfiguration();
        var passwordEncoder = configuration.passwordEncoder();
        var userDetailsService = configuration.userDetailsService(
                properties,
                environment,
                passwordEncoder
        );

        var first = userDetailsService.loadUserByUsername("demo-user");
        ((CredentialsContainer) first).eraseCredentials();
        var second = userDetailsService.loadUserByUsername("demo-user");

        assertThat(second).isNotSameAs(first);
        assertThat(second.getPassword()).isNotBlank();
        assertThat(passwordEncoder.matches("test-only-password", second.getPassword())).isTrue();
    }

    @Test
    void productionWithoutFormalBearerAuthenticationFailsClosedAtStartup() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        var properties = new DemoPrincipalProperties(false, "", "");

        assertThatThrownBy(() -> new SecurityConfiguration().userDetailsService(
                properties,
                environment,
                new SecurityConfiguration().passwordEncoder()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("formal Bearer authentication")
                .hasMessageContaining("fail-closed");
    }

    @Test
    void nonProductionWithoutDemoPrincipalRejectsEveryLocalIdentity() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("development");
        var configuration = new SecurityConfiguration();
        var userDetailsService = configuration.userDetailsService(
                new DemoPrincipalProperties(false, "", ""),
                environment,
                configuration.passwordEncoder()
        );

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("any-user"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("No local principals");
    }
}
