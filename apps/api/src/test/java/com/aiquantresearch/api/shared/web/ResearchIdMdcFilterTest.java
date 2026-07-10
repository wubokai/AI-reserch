package com.aiquantresearch.api.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ResearchIdMdcFilterTest {

    private final ResearchIdMdcFilter filter = new ResearchIdMdcFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void exposesCanonicalResearchIdDuringRequestAndClearsItAfterward() throws Exception {
        UUID researchId = UUID.randomUUID();
        var request = new MockHttpServletRequest(
                "GET",
                "/api/v1/research/" + researchId + "/status"
        );
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                assertThat(MDC.get(ResearchIdMdcFilter.RESEARCH_ID_MDC_KEY))
                        .isEqualTo(researchId.toString())
        );

        assertThat(MDC.get(ResearchIdMdcFilter.RESEARCH_ID_MDC_KEY)).isNull();
    }

    @Test
    void ignoresNonCanonicalResearchPath() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/research/not-a-uuid/status");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                assertThat(MDC.get(ResearchIdMdcFilter.RESEARCH_ID_MDC_KEY)).isNull()
        );
    }
}
