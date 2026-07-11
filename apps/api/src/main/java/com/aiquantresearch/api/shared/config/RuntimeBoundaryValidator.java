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
    }

    private boolean hasProfile(String profile) {
        return Arrays.asList(environment.getActiveProfiles()).contains(profile);
    }
}
