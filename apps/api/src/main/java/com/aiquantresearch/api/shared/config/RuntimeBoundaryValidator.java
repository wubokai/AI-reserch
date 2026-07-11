package com.aiquantresearch.api.shared.config;

import com.aiquantresearch.api.shared.domain.DataMode;
import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RuntimeBoundaryValidator {

    private final ApplicationProperties applicationProperties;
    private final Environment environment;

    public RuntimeBoundaryValidator(
            ApplicationProperties applicationProperties,
            Environment environment
    ) {
        this.applicationProperties = applicationProperties;
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        boolean production = hasProfile("production");
        boolean test = hasProfile("test");

        if (applicationProperties.dataMode() == DataMode.MIXED_TEST && !test) {
            throw new IllegalStateException("MIXED_TEST data mode is restricted to the test profile");
        }
        if (production && applicationProperties.dataMode() != DataMode.REAL) {
            throw new IllegalStateException("The production profile requires REAL data mode");
        }
        String filingProvider = environment.getProperty("app.providers.filing");
        filingProvider = filingProvider == null ? "mock" : filingProvider;
        if (applicationProperties.dataMode() == DataMode.MOCK
                && !"mock".equalsIgnoreCase(filingProvider)) {
            throw new IllegalStateException(
                    "MOCK data mode cannot enable a real filing provider"
            );
        }
        String macroProvider = environment.getProperty("app.providers.macro");
        macroProvider = macroProvider == null ? "mock" : macroProvider;
        if (applicationProperties.dataMode() == DataMode.MOCK
                && !"mock".equalsIgnoreCase(macroProvider)) {
            throw new IllegalStateException(
                    "MOCK data mode cannot enable a real macro provider"
            );
        }
        String marketProvider = provider("app.providers.market");
        String fundamentalProvider = provider("app.providers.fundamental");
        if (applicationProperties.dataMode() == DataMode.MOCK
                && !"mock".equalsIgnoreCase(marketProvider)) {
            throw new IllegalStateException(
                    "MOCK data mode cannot enable a real market provider"
            );
        }
        if (applicationProperties.dataMode() == DataMode.MOCK
                && !"mock".equalsIgnoreCase(fundamentalProvider)) {
            throw new IllegalStateException(
                    "MOCK data mode cannot enable a real fundamental provider"
            );
        }
        if (!"mock".equalsIgnoreCase(marketProvider)) {
            boolean confirmed = environment.getProperty(
                    "app.providers.market-license-confirmed",
                    Boolean.class,
                    false
            );
            String policyVersion = environment.getProperty(
                    "app.providers.market-license-policy-version"
            );
            if (!confirmed || policyVersion == null || policyVersion.isBlank()) {
                throw new IllegalStateException(
                        "A real market provider requires confirmed storage and export rights"
                );
            }
        }
    }

    private boolean hasProfile(String profile) {
        return Arrays.asList(environment.getActiveProfiles()).contains(profile);
    }

    private String provider(String property) {
        String value = environment.getProperty(property);
        return value == null ? "mock" : value;
    }
}
