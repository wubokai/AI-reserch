package com.aiquantresearch.api.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jwt.JwtException;
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
    void productionAcceptsConfiguredServiceBearerBoundary() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        environment.setProperty("app.security.service-jwt.enabled", "true");
        var configuration = new SecurityConfiguration();

        assertThatNoException().isThrownBy(() -> configuration.userDetailsService(
                new DemoPrincipalProperties(false, "", ""),
                environment,
                configuration.passwordEncoder()
        ));

        var properties = new ServiceJwtProperties(
                true,
                "ai-quant-web",
                "ai-quant-api",
                Base64.getEncoder().encodeToString(new byte[32]),
                "primary-owner",
                "bw2754@nyu.edu",
                Duration.ofSeconds(60)
        );
        assertThatNoException().isThrownBy(() -> configuration.serviceJwtDecoder(properties));
    }

    @Test
    void rejectsWeakServiceJwtSecret() {
        var properties = new ServiceJwtProperties(
                true,
                "ai-quant-web",
                "ai-quant-api",
                Base64.getEncoder().encodeToString(new byte[16]),
                "primary-owner",
                "bw2754@nyu.edu",
                Duration.ofSeconds(60)
        );

        assertThatThrownBy(properties::requireSecretBytes)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("256 bits");
    }

    @Test
    void decodesOnlyTheConfiguredShortLivedOwnerToken() throws Exception {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 7);
        var properties = new ServiceJwtProperties(
                true,
                "ai-quant-web",
                "ai-quant-api",
                Base64.getEncoder().encodeToString(secret),
                "primary-owner",
                "bw2754@nyu.edu",
                Duration.ofSeconds(60)
        );
        var decoder = new SecurityConfiguration().serviceJwtDecoder(properties);

        var decoded = decoder.decode(token(secret, "primary-owner", "bw2754@nyu.edu"));

        assertThat(decoded.getSubject()).isEqualTo("primary-owner");
        assertThat(decoded.getClaimAsString("email")).isEqualTo("bw2754@nyu.edu");
        assertThatThrownBy(() -> decoder.decode(token(
                secret,
                "another-owner",
                "bw2754@nyu.edu"
        ))).isInstanceOf(JwtException.class);
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

    private static String token(byte[] secret, String subject, String email) throws Exception {
        long now = Instant.now().getEpochSecond();
        String header = url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = url("""
                {"iss":"ai-quant-web","aud":"ai-quant-api","sub":"%s",\
                "email":"%s","iat":%d,"nbf":%d,"exp":%d}
                """.formatted(subject, email, now, now - 5, now + 60)
                .replace("\n", ""));
        String input = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal(input.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static String url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8)
        );
    }
}
