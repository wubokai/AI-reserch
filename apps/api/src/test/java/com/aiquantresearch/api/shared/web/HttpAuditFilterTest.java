package com.aiquantresearch.api.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HttpAuditFilterTest {

    @Test
    void routeLoggingRemovesResearchIdentifiersAndUnsafeCharacters() {
        assertThat(HttpAuditFilter.safeRoute(
                "/api/v1/research/11111111-1111-4111-8111-111111111111/reports/1"
        )).isEqualTo("/api/v1/research/{researchId}/reports/1");
        assertThat(HttpAuditFilter.safeRoute("/api/v1/search/<token>"))
                .isEqualTo("/unmatched");
    }

    @Test
    void routeLoggingNeverIncludesQueryStrings() {
        assertThat(HttpAuditFilter.safeRoute("/api/v1/research"))
                .doesNotContain("?", "api_key", "Authorization");
    }
}
