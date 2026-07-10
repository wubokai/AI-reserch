package com.aiquantresearch.api.health.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiquantresearch.api.health.application.HealthComponentSnapshot;
import com.aiquantresearch.api.health.application.HealthService;
import com.aiquantresearch.api.health.application.HealthSnapshot;
import com.aiquantresearch.api.health.domain.ServiceStatus;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.aiquantresearch.api.shared.security.SecurityConfiguration;
import com.aiquantresearch.api.shared.web.RequestIdFilter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@WebMvcTest(HealthController.class)
@Import({SecurityConfiguration.class, RequestIdFilter.class})
class HealthControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthService healthService;

    @BeforeEach
    void setUpHealth() {
        when(healthService.currentHealth()).thenReturn(snapshot(
                ServiceStatus.UP,
                up("database", true),
                up("durableQueue", true),
                up("redis", false)
        ));
    }

    @Test
    void allUpIsPublicAndMatchesOpenApiContract() throws Exception {
        mockMvc.perform(get("/api/v1/health").header("X-Request-Id", "req-client-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "req-client-123"))
                .andExpect(jsonPath("$.service").doesNotExist())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.version").value("0.2.0-test"))
                .andExpect(jsonPath("$.dataMode").value("MOCK"))
                .andExpect(jsonPath("$.timestamp").value("2026-07-10T12:00:00Z"))
                .andExpect(jsonPath("$.components.database.status").value("UP"))
                .andExpect(jsonPath("$.components.database.critical").value(true))
                .andExpect(jsonPath("$.components.database.latencyMs").value(3))
                .andExpect(jsonPath("$.components.database.message").value("database available"))
                .andExpect(jsonPath("$.components.durableQueue.status").value("UP"))
                .andExpect(jsonPath("$.components.redis.critical").value(false));
    }

    @Test
    void criticalDownReturnsServiceUnavailable() throws Exception {
        when(healthService.currentHealth()).thenReturn(snapshot(
                ServiceStatus.DOWN,
                down("database", true),
                down("durableQueue", true),
                up("redis", false)
        ));

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.database.message")
                        .value("Component unavailable"));
    }

    @Test
    void optionalDownReturnsDegradedWithHttp200() throws Exception {
        when(healthService.currentHealth()).thenReturn(snapshot(
                ServiceStatus.DEGRADED,
                up("database", true),
                up("durableQueue", true),
                down("redis", false)
        ));

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.components.redis.status").value("DOWN"))
                .andExpect(jsonPath("$.components.redis.critical").value(false));
    }

    @Test
    void protectedApiRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/providers/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void prometheusIsNotPublic() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void missingRequestIdIsGenerated() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.matchesPattern(
                        "req_[0-9a-f-]{36}"
                )));
    }

    private static HealthSnapshot snapshot(
            ServiceStatus status,
            NamedComponent... namedComponents
    ) {
        Map<String, HealthComponentSnapshot> components = new LinkedHashMap<>();
        for (NamedComponent named : namedComponents) {
            components.put(named.name(), named.component());
        }
        return new HealthSnapshot(status, "0.2.0-test", DataMode.MOCK, NOW, components);
    }

    private static NamedComponent up(String name, boolean critical) {
        return new NamedComponent(
                name,
                new HealthComponentSnapshot(
                        ServiceStatus.UP,
                        critical,
                        3L,
                        name + " available"
                )
        );
    }

    private static NamedComponent down(String name, boolean critical) {
        return new NamedComponent(
                name,
                new HealthComponentSnapshot(
                        ServiceStatus.DOWN,
                        critical,
                        4L,
                        "Component unavailable"
                )
        );
    }

    private record NamedComponent(String name, HealthComponentSnapshot component) {
    }
}
