package com.aiquantresearch.api.shared.security;

import com.aiquantresearch.api.shared.web.ApiErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(DemoPrincipalProperties.class)
public class SecurityConfiguration {

    private static final Set<String> DEMO_ALLOWED_PROFILES = Set.of("development", "test");
    private static final int MINIMUM_DEMO_PASSWORD_LENGTH = 16;

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            SecurityProblemWriter problemWriter,
            Environment environment,
            ObjectProvider<JwtDecoder> jwtDecoderProvider
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'none'; base-uri 'none'; "
                                        + "frame-ancestors 'none'; form-action 'none'"
                        ))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER
                        ))
                        .addHeaderWriter(new StaticHeadersWriter(
                                "Permissions-Policy",
                                "camera=(), geolocation=(), microphone=(), payment=(), usb=()"
                        )))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> problemWriter.write(
                                response,
                                ApiErrorCode.UNAUTHORIZED,
                                "Authentication is required"
                        ))
                        .accessDeniedHandler((request, response, exception) -> problemWriter.write(
                                response,
                                ApiErrorCode.FORBIDDEN,
                                "The current principal cannot access this resource"
                        )));

        if (isProduction(environment)) {
            JwtDecoder decoder = jwtDecoderProvider.getIfAvailable();
            if (decoder == null) {
                throw new IllegalStateException(
                        "Production startup is fail-closed without a service JWT decoder"
                );
            }
            http.oauth2ResourceServer(oauth -> oauth
                    .jwt(jwt -> jwt.decoder(decoder))
                    .authenticationEntryPoint((request, response, exception) -> problemWriter.write(
                            response,
                            ApiErrorCode.UNAUTHORIZED,
                            "Authentication is required"
                    )));
        } else {
            http.httpBasic(Customizer.withDefaults());
        }

        return http.build();
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.security.service-jwt.enabled",
            havingValue = "true"
    )
    JwtDecoder serviceJwtDecoder(ServiceJwtProperties properties) {
        SecretKey key = new SecretKeySpec(properties.requireSecretBytes(), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefaultWithIssuer(
                properties.issuer()
        );
        OAuth2TokenValidator<Jwt> audience = token -> token.getAudience().contains(
                properties.audience()
        ) ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "The JWT audience is invalid", null)
        );
        OAuth2TokenValidator<Jwt> owner = token -> properties.subject().equals(
                token.getSubject()
        ) && properties.email().equalsIgnoreCase(token.getClaimAsString("email"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "The JWT owner is invalid", null)
                );
        decoder.setJwtValidator(token -> {
            OAuth2TokenValidatorResult standard = defaults.validate(token);
            if (standard.hasErrors()) {
                return standard;
            }
            OAuth2TokenValidatorResult audienceResult = audience.validate(token);
            return audienceResult.hasErrors() ? audienceResult : owner.validate(token);
        });
        return decoder;
    }

    @Bean
    SecurityProblemWriter securityProblemWriter(
            ObjectMapper objectMapper,
            ObjectProvider<Clock> clockProvider
    ) {
        return new SecurityProblemWriter(
                objectMapper,
                clockProvider.getIfAvailable(Clock::systemUTC)
        );
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(
            DemoPrincipalProperties properties,
            Environment environment,
            PasswordEncoder passwordEncoder
    ) {
        validateProductionAuthenticationBoundary(properties, environment);
        if (!properties.enabled()) {
            return username -> {
                throw new UsernameNotFoundException("No local principals are configured");
            };
        }

        validateDemoBoundary(properties, environment);
        var encodedPassword = passwordEncoder.encode(properties.password());
        return username -> {
            if (!properties.username().equals(username)) {
                throw new UsernameNotFoundException("Principal not found");
            }
            // Spring Security erases credentials after authentication. Return a fresh
            // principal so one Basic-auth request cannot invalidate later requests.
            return User.withUsername(properties.username())
                    .password(encodedPassword)
                    .roles("USER")
                    .build();
        };
    }

    static void validateProductionAuthenticationBoundary(
            DemoPrincipalProperties properties,
            Environment environment
    ) {
        boolean production = isProduction(environment);
        if (!production) {
            return;
        }
        if (properties.enabled()) {
            validateDemoBoundary(properties, environment);
        }
        if (!environment.getProperty(
                "app.security.service-jwt.enabled",
                Boolean.class,
                false
        )) {
            throw new IllegalStateException(
                    "Production startup is fail-closed until formal Bearer authentication "
                            + "is configured"
            );
        }
    }

    static void validateDemoBoundary(DemoPrincipalProperties properties, Environment environment) {
        boolean allowedProfile = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(DEMO_ALLOWED_PROFILES::contains);
        if (!allowedProfile) {
            throw new IllegalStateException(
                    "Demo principal is restricted to the development and test profiles"
            );
        }
        if (properties.username() == null || properties.username().isBlank()) {
            throw new IllegalStateException("Demo principal username must be explicitly configured");
        }
        if (properties.password() == null
                || properties.password().length() < MINIMUM_DEMO_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "Demo principal password must be explicitly configured with at least 16 characters"
            );
        }
    }

    private static boolean isProduction(Environment environment) {
        return Arrays.asList(environment.getActiveProfiles()).contains("production");
    }
}
