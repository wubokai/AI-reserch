package com.aiquantresearch.api.research.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aiquantresearch.api.research.analytics.AnalyticsClient;
import com.aiquantresearch.api.research.analytics.AnalyticsRequestFactory;
import com.aiquantresearch.api.research.llm.ResearchLanguageModelRouter;
import com.aiquantresearch.api.research.provider.FilingProvider;
import com.aiquantresearch.api.research.provider.FundamentalDataProvider;
import com.aiquantresearch.api.research.provider.MacroDataProvider;
import com.aiquantresearch.api.research.provider.MarketDataProvider;
import com.aiquantresearch.api.research.provider.mock.MockFixtureCatalog;
import com.aiquantresearch.api.research.report.ReportRepairService;
import com.aiquantresearch.api.research.report.ReportValidator;
import com.aiquantresearch.api.research.worker.StepExecutionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@SuppressWarnings("unchecked")
class Phase7RealOrchestrationTest {

    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private Phase3StepExecutor executor;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        objectMapper = new ObjectMapper();
        executor = new Phase3StepExecutor(
                mock(Phase3ArtifactStore.class),
                mock(MarketDataProvider.class),
                mock(FundamentalDataProvider.class),
                mock(FilingProvider.class),
                mock(MacroDataProvider.class),
                mock(MockFixtureCatalog.class),
                mock(AnalyticsRequestFactory.class),
                mock(AnalyticsClient.class),
                mock(ResearchLanguageModelRouter.class),
                mock(ReportValidator.class),
                mock(ReportRepairService.class),
                jdbc,
                objectMapper
        );
    }

    @Test
    void realResolutionUsesOnlyTheActiveNonDemoSecurityMaster() {
        ObjectNode security = objectMapper.createObjectNode()
                .put("symbol", "AAPL")
                .put("demoData", false);
        when(jdbc.query(
                argThat(sql -> sql.contains("active and not is_demo_data")),
                any(RowMapper.class),
                eq("AAPL")
        )).thenReturn(List.of(security));

        StepExecutionResult result = executor.resolveRealSecurity("AAPL");

        assertThat(result.payload().path("symbol").asText()).isEqualTo("AAPL");
        assertThat(result.payload().path("demoData").asBoolean()).isFalse();
    }

    @Test
    void missingRealSecurityFailsWithoutMockFallback() {
        when(jdbc.query(any(String.class), any(RowMapper.class), eq("AAPL")))
                .thenReturn(List.of());

        assertThatThrownBy(() -> executor.resolveRealSecurity("AAPL"))
                .isInstanceOfSatisfying(StepExecutionException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("REAL_SECURITY_MASTER_MISSING"));
    }
}
