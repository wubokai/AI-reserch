package com.aiquantresearch.api.research.artifactapi;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiquantresearch.api.research.application.AuthenticatedOwnerService;
import com.aiquantresearch.api.research.application.OwnerProvisioningService;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.aiquantresearch.api.shared.security.CurrentUserResolver;
import com.aiquantresearch.api.shared.security.SecurityConfiguration;
import com.aiquantresearch.api.shared.web.GlobalApiExceptionHandler;
import com.aiquantresearch.api.shared.web.RequestIdFilter;
import com.aiquantresearch.api.shared.web.ResearchIdMdcFilter;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@WebMvcTest(ArtifactApiController.class)
@Import({
        SecurityConfiguration.class,
        CurrentUserResolver.class,
        AuthenticatedOwnerService.class,
        GlobalApiExceptionHandler.class,
        RequestIdFilter.class,
        ResearchIdMdcFilter.class
})
class ArtifactApiControllerTest {

    private static final String ALICE = "alice@example.com";
    private static final UUID ALICE_ID = UUID.nameUUIDFromBytes(
            ("ai-quant-research:user:v1:" + ALICE).getBytes(StandardCharsets.UTF_8)
    );
    private static final UUID RESEARCH_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );
    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ArtifactQueryService queryService;

    @MockitoBean
    private ReportExportService exportService;

    @MockitoBean
    private OwnerProvisioningService ownerProvisioningService;

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void evidenceUsesAuthenticatedOwnerAndPreservesPublicIdCharacters() throws Exception {
        var evidence = new ArtifactApiResponses.EvidenceItem(
                "ev_MU_return-01",
                "QUANT_RESULT",
                "Five year return",
                "Deterministic result",
                JsonNodeFactory.instance.numberNode(0.42),
                "ratio",
                "Internal Analytics",
                null,
                "INTERNAL_CALCULATION",
                null,
                NOW,
                LocalDate.parse("2025-12-31"),
                true,
                "FRESH",
                0.96,
                "a".repeat(64),
                true,
                List.of("cl_MU-return_01")
        );
        when(queryService.evidence(
                ALICE_ID,
                RESEARCH_ID,
                1,
                10,
                "CALCULATION",
                "INTERNAL_CALCULATION",
                "FRESH",
                true
        )).thenReturn(new ArtifactApiResponses.EvidencePage(
                List.of(evidence),
                new ArtifactApiResponses.PageMetadata(1, 10, 11, 2, false, true),
                DataMode.MOCK
        ));

        mockMvc.perform(get("/api/v1/research/{researchId}/evidence", RESEARCH_ID)
                        .param("page", "1")
                        .param("size", "10")
                        .param("claimType", "CALCULATION")
                        .param("sourceType", "INTERNAL_CALCULATION")
                        .param("freshnessStatus", "FRESH")
                        .param("isDemoData", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].evidenceId").value("ev_MU_return-01"))
                .andExpect(jsonPath("$.items[0].relatedClaimIds[0]")
                        .value("cl_MU-return_01"))
                .andExpect(jsonPath("$.dataMode").value("MOCK"))
                .andExpect(jsonPath("$.page.totalElements").value(11));

        verify(queryService).evidence(
                ALICE_ID,
                RESEARCH_ID,
                1,
                10,
                "CALCULATION",
                "INTERNAL_CALCULATION",
                "FRESH",
                true
        );
        verify(ownerProvisioningService).ensureOwner(ALICE_ID, ALICE, ALICE);
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void reportReturnsCanonicalEnvelopeAndContentEtag() throws Exception {
        var reportJson = JsonNodeFactory.instance.objectNode()
                .put("schemaVersion", "research_report_v1")
                .put("title", "MU report");
        when(queryService.report(ALICE_ID, RESEARCH_ID, 1))
                .thenReturn(new ArtifactApiResponses.ReportVersionResponse(
                        RESEARCH_ID,
                        1,
                        "PASSED_WITH_WARNINGS",
                        "b".repeat(64),
                        NOW,
                        NOW.minusSeconds(1),
                        reportJson
                ));

        mockMvc.perform(get("/api/v1/research/{researchId}/reports/{version}", RESEARCH_ID, 1))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"" + "b".repeat(64) + "\""))
                .andExpect(jsonPath("$.researchId").value(RESEARCH_ID.toString()))
                .andExpect(jsonPath("$.validationStatus").value("PASSED_WITH_WARNINGS"))
                .andExpect(jsonPath("$.report.schemaVersion").value("research_report_v1"))
                .andExpect(jsonPath("$.report.title").value("MU report"));
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void reportListMapsVersionMetadata() throws Exception {
        when(queryService.reports(ALICE_ID, RESEARCH_ID, 0, 20))
                .thenReturn(new ArtifactApiResponses.ReportVersionPage(
                        List.of(new ArtifactApiResponses.ReportVersionSummary(
                                RESEARCH_ID,
                                2,
                                "MU report",
                                "MU",
                                LocalDate.parse("2025-12-31"),
                                "PASSED",
                                DataMode.MOCK,
                                "c".repeat(64),
                                NOW
                        )),
                        new ArtifactApiResponses.PageMetadata(0, 20, 1, 1, true, true)
                ));

        mockMvc.perform(get("/api/v1/research/{researchId}/reports", RESEARCH_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].version").value(2))
                .andExpect(jsonPath("$.items[0].dataMode").value("MOCK"))
                .andExpect(jsonPath("$.page.last").value(true));
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void securitySearchAndProviderStatusRequireAnActivePrincipal() throws Exception {
        when(queryService.searchSecurities("mu", "COMMON_STOCK", "NASDAQ", 5))
                .thenReturn(new ArtifactApiResponses.SecuritySearchResponse(
                        List.of(new ArtifactApiResponses.SecurityItem(
                                UUID.randomUUID(),
                                "MU",
                                "Micron Technology, Inc.",
                                "NASDAQ",
                                "COMMON_STOCK",
                                "USD",
                                null,
                                null,
                                null,
                                true,
                                DataMode.MOCK
                        )),
                        DataMode.MOCK
                ));
        when(queryService.providerStatus()).thenReturn(
                new ArtifactApiResponses.ProviderStatusResponse("UP", DataMode.MOCK, List.of(), NOW)
        );

        mockMvc.perform(get("/api/v1/securities/search")
                        .param("q", "mu")
                        .param("securityType", "COMMON_STOCK")
                        .param("exchange", "NASDAQ")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].symbol").value("MU"));
        mockMvc.perform(get("/api/v1/providers/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        verify(queryService).searchSecurities(eq("mu"), eq("COMMON_STOCK"), eq("NASDAQ"), eq(5));
        verify(queryService).providerStatus();
    }

    @Test
    void artifactRoutesRejectAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/v1/research/{researchId}/evidence", RESEARCH_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void exportAcceptsWebLowercaseFormatAndReturnsDeterministicHeaders() throws Exception {
        byte[] markdown = "# MU\n\nDEMO DATA — NOT REAL MARKET DATA".getBytes(StandardCharsets.UTF_8);
        when(exportService.export(
                ALICE_ID,
                RESEARCH_ID,
                ReportExportFormat.MARKDOWN,
                2
        )).thenReturn(new ReportExportArtifact(
                markdown,
                "d".repeat(64),
                ReportExportFormat.MARKDOWN,
                "MU-research-v2.md",
                2,
                DataMode.MOCK
        ));

        mockMvc.perform(get("/api/v1/research/{researchId}/export", RESEARCH_ID)
                        .param("format", "markdown")
                        .param("reportVersion", "2"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/markdown;charset=UTF-8"))
                .andExpect(content().bytes(markdown))
                .andExpect(header().string(
                        "Content-Disposition",
                        "attachment; filename=\"MU-research-v2.md\""
                ))
                .andExpect(header().string("ETag", "\"" + "d".repeat(64) + "\""))
                .andExpect(header().string("X-Report-Version", "2"))
                .andExpect(header().string("X-Data-Mode", "MOCK"))
                .andExpect(header().string("X-Content-SHA256", "d".repeat(64)));
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void exportFailureReturnsOnlyTheSafePublicProblem() throws Exception {
        when(exportService.export(ALICE_ID, RESEARCH_ID, ReportExportFormat.PDF, null))
                .thenThrow(new ReportExportException(RESEARCH_ID));

        mockMvc.perform(get("/api/v1/research/{researchId}/export", RESEARCH_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EXPORT_RENDER_FAILED"))
                .andExpect(jsonPath("$.message")
                        .value("The requested report format could not be rendered"))
                .andExpect(jsonPath("$.researchId").value(RESEARCH_ID.toString()))
                .andExpect(jsonPath("$.retryable").value(true));
    }
}
