package com.aiquantresearch.api.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void echoesSafeRequestIdAndClearsMdcAfterRequest() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "req-client:123");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("req-client:123");
        assertThat(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY)).isNull();
    }

    @Test
    void replacesUnsafeRequestId() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "unsafe request id");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER))
                .matches("req_[0-9a-f-]{36}")
                .isNotEqualTo("unsafe request id");
    }

    @Test
    void restoresPreexistingMdcValue() throws Exception {
        MDC.put(RequestIdFilter.REQUEST_ID_MDC_KEY, "outer-request");
        var request = new MockHttpServletRequest("GET", "/api/v1/health");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY)).isEqualTo("outer-request");
    }
}
