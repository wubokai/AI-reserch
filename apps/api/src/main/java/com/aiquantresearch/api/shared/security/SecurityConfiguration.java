package com.aiquantresearch.api.shared.security;

import java.util.Arrays;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(DemoPrincipalProperties.class)
public class SecurityConfiguration {

    private static final Set<String> DEMO_ALLOWED_PROFILES = Set.of("development", "test");
    private static final int MINIMUM_DEMO_PASSWORD_LENGTH = 16;

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        return http.build();
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
        if (!properties.enabled()) {
            return username -> {
                throw new UsernameNotFoundException("No local principals are configured");
            };
        }

        validateDemoBoundary(properties, environment);
        var demoUser = User.withUsername(properties.username())
                .password(passwordEncoder.encode(properties.password()))
                .roles("USER")
                .build();
        return username -> {
            if (!demoUser.getUsername().equals(username)) {
                throw new UsernameNotFoundException("Principal not found");
            }
            return demoUser;
        };
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
}
