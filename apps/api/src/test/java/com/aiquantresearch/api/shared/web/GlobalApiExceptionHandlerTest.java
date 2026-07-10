package com.aiquantresearch.api.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiquantresearch.api.research.application.ResearchApplicationException;
import com.aiquantresearch.api.research.application.ResearchNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;

class GlobalApiExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    private final GlobalApiExceptionHandler handler = new GlobalApiExceptionHandler(
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void rendersContractCompatibleProblemResponse() {
        UUID researchId = UUID.randomUUID();
        MDC.put(RequestIdFilter.REQUEST_ID_MDC_KEY, "req_test");
        var exception = new ApiException(
                ApiErrorCode.INVALID_STATE_TRANSITION,
                "Cannot retry a queued research job",
                researchId,
                Map.of("currentStatus", "QUEUED")
        );

        var response = handler.handleApiException(exception);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().timestamp()).isEqualTo(NOW);
        assertThat(response.getBody().code()).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(response.getBody().requestId()).isEqualTo("req_test");
        assertThat(response.getBody().researchId()).isEqualTo(researchId);
        assertThat(response.getBody().details()).containsEntry("currentStatus", "QUEUED");
    }

    @Test
    void preservesIdempotencyInProgressApplicationCodeAsConflict() {
        var exception = new ResearchApplicationException(
                "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                "An identical request is still being processed; retry shortly",
                true
        );

        var response = handler.handleResearchApplicationException(exception);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("IDEMPOTENCY_REQUEST_IN_PROGRESS");
        assertThat(response.getBody().retryable()).isTrue();
    }

    @Test
    void researchNotFoundCarriesTheRequestedIdentifier() {
        UUID researchId = UUID.randomUUID();

        var response = handler.handleResearchApplicationException(
                new ResearchNotFoundException(researchId)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("RESEARCH_NOT_FOUND");
        assertThat(response.getBody().researchId()).isEqualTo(researchId);
    }
}
