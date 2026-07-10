package com.aiquantresearch.api.health.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiquantresearch.api.health.application.HealthService;
import com.aiquantresearch.api.health.application.HealthSnapshot;
import com.aiquantresearch.api.health.domain.ServiceStatus;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.aiquantresearch.api.shared.security.SecurityConfiguration;
import com.aiquantresearch.api.shared.web.RequestIdFilter;
import java.time.Instant;
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

    private static final Instant NOW = Instant.parse("2026-07-09T18:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthService healthService;

    @BeforeEach
    void setUpHealth() {
        when(healthService.currentHealth()).thenReturn(new HealthSnapshot(
                "ai-quant-research-api",
                ServiceStatus.UP,
                "0.1.0-test",
                NOW,
                DataMode.MOCK
        ));
    }

    @Test
    void healthIsPublicAndMatchesTheBaselineContract() throws Exception {
        mockMvc.perform(get("/api/v1/health").header("X-Request-Id", "req-client-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "req-client-123"))
                .andExpect(jsonPath("$.service").value("ai-quant-research-api"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.version").value("0.1.0-test"))
                .andExpect(jsonPath("$.timestamp").value("2026-07-09T18:00:00Z"))
                .andExpect(jsonPath("$.dataMode").value("MOCK"));
    }

    @Test
    void protectedApiRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/providers/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Request-Id"));
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
}
