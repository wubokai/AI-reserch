package com.aiquantresearch.api.research.web;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiquantresearch.api.research.application.AccountAccessDeniedException;
import com.aiquantresearch.api.research.application.AuthenticatedOwnerService;
import com.aiquantresearch.api.research.application.CommandResult;
import com.aiquantresearch.api.research.application.CreateResearchCommand;
import com.aiquantresearch.api.research.application.InvalidSymbolException;
import com.aiquantresearch.api.research.application.InvalidStateTransitionException;
import com.aiquantresearch.api.research.application.OwnerProvisioningService;
import com.aiquantresearch.api.research.application.PageMetadataView;
import com.aiquantresearch.api.research.application.ResearchAcceptedView;
import com.aiquantresearch.api.research.application.ResearchApplicationException;
import com.aiquantresearch.api.research.application.ResearchCommandService;
import com.aiquantresearch.api.research.application.ResearchDetailView;
import com.aiquantresearch.api.research.application.ResearchItemView;
import com.aiquantresearch.api.research.application.ResearchListQuery;
import com.aiquantresearch.api.research.application.ResearchNotFoundException;
import com.aiquantresearch.api.research.application.ResearchPageView;
import com.aiquantresearch.api.research.application.ResearchQueryService;
import com.aiquantresearch.api.research.application.ResearchSort;
import com.aiquantresearch.api.research.application.ResearchStatusView;
import com.aiquantresearch.api.research.application.ResearchStepView;
import com.aiquantresearch.api.research.application.RetryResearchCommand;
import com.aiquantresearch.api.research.application.SecurityMismatchException;
import com.aiquantresearch.api.research.domain.ReportDepth;
import com.aiquantresearch.api.research.domain.ResearchLocale;
import com.aiquantresearch.api.research.domain.ResearchPeriod;
import com.aiquantresearch.api.research.domain.ResearchStatus;
import com.aiquantresearch.api.research.domain.StepStatus;
import com.aiquantresearch.api.research.domain.StepType;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.aiquantresearch.api.shared.security.CurrentUserResolver;
import com.aiquantresearch.api.shared.security.SecurityConfiguration;
import com.aiquantresearch.api.shared.web.GlobalApiExceptionHandler;
import com.aiquantresearch.api.shared.web.RequestIdFilter;
import com.aiquantresearch.api.shared.web.ResearchIdMdcFilter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@WebMvcTest(ResearchController.class)
@Import({
        SecurityConfiguration.class,
        CurrentUserResolver.class,
        AuthenticatedOwnerService.class,
        GlobalApiExceptionHandler.class,
        RequestIdFilter.class,
        ResearchIdMdcFilter.class
})
class ResearchControllerTest {

    private static final String ALICE = "alice@example.com";
    private static final UUID ALICE_ID = UUID.nameUUIDFromBytes(
            ("ai-quant-research:user:v1:" + ALICE).getBytes(StandardCharsets.UTF_8)
    );
    private static final UUID RESEARCH_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResearchCommandService commandService;

    @MockitoBean
    private ResearchQueryService queryService;

    @MockitoBean
    private OwnerProvisioningService ownerProvisioningService;

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void createUsesAuthenticatedOwnerAndReturnsAcceptedHeaders() throws Exception {
        when(commandService.create(
                eq(ALICE_ID),
                eq(ALICE),
                eq(ALICE),
                eq("create-1"),
                any(CreateResearchCommand.class)
        )).thenReturn(new CommandResult<>(acceptedView(), true));

        mockMvc.perform(post("/api/v1/research")
                        .header("Idempotency-Key", "create-1")
                        .header("X-Owner-Id", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/research/" + RESEARCH_ID))
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.researchId").value(RESEARCH_ID.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.dataMode").value("MOCK"))
                .andExpect(jsonPath("$.links.status")
                        .value("/api/v1/research/" + RESEARCH_ID + "/status"));

        ArgumentCaptor<CreateResearchCommand> command =
                ArgumentCaptor.forClass(CreateResearchCommand.class);
        verify(commandService).create(
                eq(ALICE_ID),
                eq(ALICE),
                eq(ALICE),
                eq("create-1"),
                command.capture()
        );
        verify(ownerProvisioningService).ensureOwner(ALICE_ID, ALICE, ALICE);
        org.assertj.core.api.Assertions.assertThat(command.getValue().symbol()).isEqualTo("MU");
        org.assertj.core.api.Assertions.assertThat(command.getValue().locale())
                .isEqualTo(ResearchLocale.ZH_CN);
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void listMapsFiltersAndPagination() throws Exception {
        when(queryService.list(eq(ALICE_ID), any(ResearchListQuery.class)))
                .thenReturn(new ResearchPageView(
                        List.of(itemView()),
                        new PageMetadataView(1, 10, 11, 2, false, true)
                ));

        mockMvc.perform(get("/api/v1/research")
                        .param("page", "1")
                        .param("size", "10")
                        .param("symbol", "mu")
                        .param("status", "QUEUED")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-07-01T00:00:00Z")
                        .param("q", "growth")
                        .param("sort", "updatedAt,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].researchId").value(RESEARCH_ID.toString()))
                .andExpect(jsonPath("$.items[0].symbol").value("MU"))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(11));

        ArgumentCaptor<ResearchListQuery> query = ArgumentCaptor.forClass(ResearchListQuery.class);
        verify(queryService).list(eq(ALICE_ID), query.capture());
        org.assertj.core.api.Assertions.assertThat(query.getValue().symbol()).isEqualTo("MU");
        org.assertj.core.api.Assertions.assertThat(query.getValue().sort())
                .isEqualTo(ResearchSort.UPDATED_AT_ASC);
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void detailReturnsContractResponse() throws Exception {
        when(queryService.detail(ALICE_ID, RESEARCH_ID)).thenReturn(detailView());

        mockMvc.perform(get("/api/v1/research/{researchId}", RESEARCH_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.researchId").value(RESEARCH_ID.toString()))
                .andExpect(jsonPath("$.request.period").value("5y"))
                .andExpect(jsonPath("$.request.locale").value("zh-CN"))
                .andExpect(jsonPath("$.currentStep").value("RESOLVE_SECURITY"))
                .andExpect(jsonPath("$.warnings[0].code").value("RESEARCH_WARNING"));

        verify(queryService).detail(ALICE_ID, RESEARCH_ID);
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void statusReturnsProgressAndSteps() throws Exception {
        when(queryService.status(ALICE_ID, RESEARCH_ID)).thenReturn(statusView(false));

        mockMvc.perform(get("/api/v1/research/{researchId}/status", RESEARCH_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.researchId").value(RESEARCH_ID.toString()))
                .andExpect(jsonPath("$.progress").value(5))
                .andExpect(jsonPath("$.totalSteps").value(1))
                .andExpect(jsonPath("$.steps[0].step").value("RESOLVE_SECURITY"));
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void retryReturnsStatusLocationAndMapsOptionalBody() throws Exception {
        when(commandService.retry(
                eq(ALICE_ID),
                eq(RESEARCH_ID),
                eq("retry-1"),
                any(RetryResearchCommand.class)
        )).thenReturn(new CommandResult<>(acceptedView(), false));

        mockMvc.perform(post("/api/v1/research/{researchId}/retry", RESEARCH_ID)
                        .header("Idempotency-Key", "retry-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromStep":"FETCH_MARKET_DATA","reason":"retry provider timeout"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string(
                        "Location",
                        "/api/v1/research/" + RESEARCH_ID + "/status"
                ))
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(header().string("Idempotency-Replayed", "false"));

        ArgumentCaptor<RetryResearchCommand> command =
                ArgumentCaptor.forClass(RetryResearchCommand.class);
        verify(commandService).retry(
                eq(ALICE_ID),
                eq(RESEARCH_ID),
                eq("retry-1"),
                command.capture()
        );
        org.assertj.core.api.Assertions.assertThat(command.getValue().fromStep())
                .isEqualTo(StepType.FETCH_MARKET_DATA);
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void cancelReturnsStatusSnapshot() throws Exception {
        when(commandService.cancel(eq(ALICE_ID), eq(RESEARCH_ID), eq("cancel-1"), any()))
                .thenReturn(new CommandResult<>(statusView(true), false));

        mockMvc.perform(post("/api/v1/research/{researchId}/cancel", RESEARCH_ID)
                        .header("Idempotency-Key", "cancel-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"user requested\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(
                        "Location",
                        "/api/v1/research/" + RESEARCH_ID + "/status"
                ))
                .andExpect(jsonPath("$.cancellationRequested").value(true));
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void deleteReturnsNoContentForAuthenticatedOwner() throws Exception {
        mockMvc.perform(delete("/api/v1/research/{researchId}", RESEARCH_ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(commandService).softDelete(ALICE_ID, RESEARCH_ID);
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void everyPostRequiresIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/research")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.missingHeader").value("Idempotency-Key"));

        mockMvc.perform(post("/api/v1/research/{researchId}/retry", RESEARCH_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/v1/research/{researchId}/cancel", RESEARCH_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(commandService);
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void unknownJsonFieldIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/research")
                        .header("Idempotency-Key", "create-unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query":"Analyze MU growth drivers and material risks",
                                  "symbol":"MU",
                                  "unexpected":"must fail"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(commandService);
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void inaccessibleResearchIsIndistinguishableFromMissing() throws Exception {
        when(queryService.detail(ALICE_ID, RESEARCH_ID))
                .thenThrow(new ResearchNotFoundException(RESEARCH_ID));

        mockMvc.perform(get("/api/v1/research/{researchId}", RESEARCH_ID)
                        .header("X-Owner-Id", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESEARCH_NOT_FOUND"))
                .andExpect(jsonPath("$.researchId").value(RESEARCH_ID.toString()))
                .andExpect(jsonPath("$.retryable").value(false));

        verify(queryService).detail(ALICE_ID, RESEARCH_ID);
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void illegalRetryStateReturnsConflict() throws Exception {
        when(commandService.retry(eq(ALICE_ID), eq(RESEARCH_ID), eq("retry-conflict"), any()))
                .thenThrow(new InvalidStateTransitionException("QUEUED cannot be retried"));

        mockMvc.perform(post("/api/v1/research/{researchId}/retry", RESEARCH_ID)
                        .header("Idempotency-Key", "retry-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"))
                .andExpect(jsonPath("$.message").value("QUEUED cannot be retried"));
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void localSecurityPrecheckFailuresUseStableUnprocessableCodes() throws Exception {
        when(commandService.create(
                eq(ALICE_ID),
                anyString(),
                anyString(),
                eq("inactive-security"),
                any(CreateResearchCommand.class)
        )).thenThrow(new InvalidSymbolException(
                "The locally known security is inactive or unsupported"
        ));

        mockMvc.perform(post("/api/v1/research")
                        .header("Idempotency-Key", "inactive-security")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_SYMBOL"))
                .andExpect(jsonPath("$.researchId").value(nullValue()));

        when(commandService.create(
                eq(ALICE_ID),
                anyString(),
                anyString(),
                eq("security-mismatch"),
                any(CreateResearchCommand.class)
        )).thenThrow(new SecurityMismatchException(
                "The supplied symbol and companyName resolve to different securities"
        ));

        mockMvc.perform(post("/api/v1/research")
                        .header("Idempotency-Key", "security-mismatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SECURITY_MISMATCH"))
                .andExpect(jsonPath("$.researchId").value(nullValue()));
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void unmappedApiRouteReturnsProblemNotFoundInsteadOfInternalError() throws Exception {
        mockMvc.perform(get("/api/v1/not-a-route"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"))
                .andExpect(jsonPath("$.researchId").value(nullValue()));
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void retryableApplicationFailureReturnsServiceUnavailableWithOriginalCode() throws Exception {
        when(commandService.create(
                eq(ALICE_ID),
                anyString(),
                anyString(),
                eq("create-unavailable"),
                any(CreateResearchCommand.class)
        )).thenThrow(new ResearchApplicationException(
                "IDEMPOTENCY_RESERVATION_FAILED",
                "The idempotency reservation could not be read",
                true
        ));

        mockMvc.perform(post("/api/v1/research")
                        .header("Idempotency-Key", "create-unavailable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_RESERVATION_FAILED"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void nonRetryableUnknownApplicationFailureReturnsInternalErrorWithOriginalCode()
            throws Exception {
        when(queryService.detail(ALICE_ID, RESEARCH_ID)).thenThrow(
                new ResearchApplicationException(
                        "RESEARCH_SNAPSHOT_INVALID",
                        "The stored research request snapshot is invalid",
                        false
                )
        );

        mockMvc.perform(get("/api/v1/research/{researchId}", RESEARCH_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("RESEARCH_SNAPSHOT_INVALID"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    @WithMockUser(username = ALICE, roles = "USER")
    void disabledAccountIsDeniedAcrossEveryResearchEndpoint() throws Exception {
        when(ownerProvisioningService.ensureOwner(any(), anyString(), anyString()))
                .thenThrow(new AccountAccessDeniedException());

        mockMvc.perform(post("/api/v1/research")
                        .header("Idempotency-Key", "disabled-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.retryable").value(false));

        mockMvc.perform(get("/api/v1/research"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        mockMvc.perform(get("/api/v1/research/{researchId}", RESEARCH_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        mockMvc.perform(get("/api/v1/research/{researchId}/status", RESEARCH_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        mockMvc.perform(post("/api/v1/research/{researchId}/retry", RESEARCH_ID)
                        .header("Idempotency-Key", "disabled-retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        mockMvc.perform(post("/api/v1/research/{researchId}/cancel", RESEARCH_ID)
                        .header("Idempotency-Key", "disabled-cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        mockMvc.perform(delete("/api/v1/research/{researchId}", RESEARCH_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        verifyNoInteractions(commandService, queryService);
    }

    @Test
    void unauthenticatedRequestReturnsProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/research"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.researchId").value(nullValue()));

        verifyNoInteractions(queryService);
    }

    @Test
    void resolverAuthenticationFailureIsAlsoMappedToUnauthorized() throws Exception {
        var authentication = new TestingAuthenticationToken(" ", "unused", "ROLE_USER");

        mockMvc.perform(get("/api/v1/research").with(authentication(authentication)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(queryService);
    }

    private static String validCreateJson() {
        return """
                {
                  "query":"分析 MU 未来十二个月的主要增长动力和风险",
                  "symbol":"mu",
                  "locale":"zh-CN",
                  "benchmark":"QQQ",
                  "period":"5y",
                  "reportDepth":"STANDARD"
                }
                """;
    }

    private static ResearchAcceptedView acceptedView() {
        return new ResearchAcceptedView(RESEARCH_ID, ResearchStatus.QUEUED, DataMode.MOCK, NOW);
    }

    private static CreateResearchCommand requestCommand() {
        return new CreateResearchCommand(
                "分析 MU 未来十二个月的主要增长动力和风险",
                "MU",
                null,
                ResearchLocale.ZH_CN,
                "QQQ",
                ResearchPeriod.FIVE_YEARS,
                null,
                null,
                ReportDepth.STANDARD,
                true,
                true,
                true
        );
    }

    private static ResearchItemView itemView() {
        return new ResearchItemView(
                RESEARCH_ID,
                "MU growth research",
                requestCommand().query(),
                "MU",
                null,
                "QQQ",
                ResearchStatus.QUEUED,
                5,
                ReportDepth.STANDARD,
                DataMode.MOCK,
                null,
                NOW,
                NOW,
                null
        );
    }

    private static ResearchDetailView detailView() {
        return new ResearchDetailView(
                itemView(),
                requestCommand(),
                StepType.RESOLVE_SECURITY,
                NOW,
                false,
                null,
                List.of("DEMO DATA - NOT REAL MARKET DATA")
        );
    }

    private static ResearchStatusView statusView(boolean cancellationRequested) {
        return new ResearchStatusView(
                RESEARCH_ID,
                ResearchStatus.QUEUED,
                5,
                StepType.RESOLVE_SECURITY,
                0,
                1,
                cancellationRequested,
                DataMode.MOCK,
                null,
                List.of(new ResearchStepView(
                        StepType.RESOLVE_SECURITY,
                        StepStatus.PENDING,
                        0,
                        null,
                        null,
                        null,
                        false,
                        null
                )),
                NOW
        );
    }
}
