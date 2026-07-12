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
import com.aiquantresearch.api.research.provider.FilingDocument;
import com.aiquantresearch.api.research.provider.FilingSnapshot;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@SuppressWarnings("unchecked")
class Phase7RealOrchestrationTest {

    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private Phase3StepExecutor executor;
    private Phase3ArtifactStore artifactStore;
    private FilingProvider filingProvider;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        artifactStore = mock(Phase3ArtifactStore.class);
        filingProvider = mock(FilingProvider.class);
        executor = new Phase3StepExecutor(
                artifactStore,
                mock(MarketDataProvider.class),
                mock(FundamentalDataProvider.class),
                filingProvider,
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

    @Test
    void oversizedFilingIsMarkedForBoundedProcessingInsteadOfFailingTheStep() {
        UUID researchId = UUID.randomUUID();
        ResearchExecutionContext context = new ResearchExecutionContext(
                researchId,
                UUID.randomUUID(),
                "BIG",
                "COMMON_STOCK",
                "zh-CN",
                com.aiquantresearch.api.shared.domain.DataMode.REAL,
                objectMapper.createObjectNode().put("reportDepth", "STANDARD")
        );
        when(artifactStore.context(researchId)).thenReturn(context);
        String oversizedHtml = "<p>" + "x".repeat(
                com.aiquantresearch.api.research.filing.FilingTextProcessor
                        .maximumSourceCharacters() + 1
        ) + "</p>";
        when(filingProvider.fetch("BIG")).thenReturn(new FilingSnapshot(
                "SEC_EDGAR",
                "sec_filings_v1",
                null,
                "BIG",
                LocalDate.parse("2026-07-10"),
                Instant.parse("2026-07-10T12:00:00Z"),
                "https://data.sec.gov/submissions/CIK0000000001.json",
                "a".repeat(64),
                List.of(new FilingDocument(
                        "0000000001-26-000001:big.htm",
                        "0000000001-26-000001",
                        "10-Q",
                        LocalDate.parse("2026-07-10"),
                        LocalDate.parse("2026-06-30"),
                        "10-Q — Big Company",
                        "Quarterly filing",
                        "https://www.sec.gov/Archives/big.htm",
                        oversizedHtml
                )),
                null,
                false,
                "FRESH",
                "sec_public_edgar_2025_04_08"
        ));
        var claim = mock(com.aiquantresearch.api.research.worker.QueueClaim.class);
        when(claim.researchJobId()).thenReturn(researchId);
        when(claim.stepType()).thenReturn(
                com.aiquantresearch.api.research.domain.StepType.FETCH_FILINGS
        );

        StepExecutionResult result = executor.execute(claim);

        assertThat(result.partial()).isTrue();
        assertThat(result.warnings()).containsExactly("FILING_CONTENT_TRUNCATED");
        assertThat(result.payload().path("filings").get(0)
                .path("contentProcessingStatus").asText()).isEqualTo("BOUNDED");
    }
}
