package com.aiquantresearch.api.research.provider.sec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiquantresearch.api.research.provider.ProviderAccessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class SecEdgarFilingProviderTest {

    private static final String USER_AGENT = "AI Quant Research Assistant ops@example.test";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final List<String> userAgents = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsOfficialSubmissionMetadataAndDownloadsAllowlistedPrimaryDocument() throws Exception {
        start(exchange -> route(exchange, false));

        var snapshot = provider(2).fetch("mu");

        assertThat(snapshot.provider()).isEqualTo("SEC_EDGAR");
        assertThat(snapshot.schemaVersion()).isEqualTo("sec_filings_v1");
        assertThat(snapshot.symbol()).isEqualTo("MU");
        assertThat(snapshot.asOfDate()).isEqualTo(LocalDate.parse("2025-10-03"));
        assertThat(snapshot.demoData()).isFalse();
        assertThat(snapshot.rawDataHash()).matches("^[0-9a-f]{64}$");
        assertThat(snapshot.filings()).singleElement().satisfies(filing -> {
            assertThat(filing.accessionNumber()).isEqualTo("0000000123-25-000001");
            assertThat(filing.formType()).isEqualTo("10-K");
            assertThat(filing.reportPeriod()).isEqualTo(LocalDate.parse("2025-08-28"));
            assertThat(filing.sourceUrl()).endsWith(
                    "/Archives/edgar/data/123/000000012325000001/report.htm"
            );
            assertThat(filing.summary()).contains("Item 1 Business");
        });
        assertThat(userAgents).hasSize(3).allMatch(USER_AGENT::equals);
    }

    @Test
    void retriesOnlyTransientRateLimitWithinConfiguredAttemptBudget() throws Exception {
        AtomicInteger tickerCalls = new AtomicInteger();
        start(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/files/company_tickers.json")
                    && tickerCalls.getAndIncrement() == 0) {
                capture(exchange);
                respond(exchange, 429, "application/json", "{\"secret\":\"do-not-log\"}");
                return;
            }
            route(exchange, false);
        });

        assertThat(provider(2).fetch("MU").filings()).hasSize(1);
        assertThat(tickerCalls).hasValue(2);
    }

    @Test
    void rejectsUnsafePrimaryDocumentBeforeArchiveRequest() throws Exception {
        start(exchange -> route(exchange, true));

        assertThatThrownBy(() -> provider(1).fetch("MU"))
                .isInstanceOfSatisfying(ProviderAccessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("SEC_DOCUMENT_ID_INVALID");
                    assertThat(exception.retryable()).isFalse();
                });
        assertThat(userAgents).hasSize(2);
    }

    @Test
    void rejectsUnexpectedContentTypeWithoutRetrying() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> {
            calls.incrementAndGet();
            capture(exchange);
            respond(exchange, 200, "text/html", "<html>not ticker JSON</html>");
        });

        assertThatThrownBy(() -> provider(2).fetch("MU"))
                .isInstanceOfSatisfying(ProviderAccessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("SEC_CONTENT_TYPE_INVALID");
                    assertThat(exception.retryable()).isFalse();
                });
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsEmptyResponseWithoutRetrying() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> {
            calls.incrementAndGet();
            capture(exchange);
            respond(exchange, 200, "application/json", "");
        });

        assertThatThrownBy(() -> provider(2).fetch("MU"))
                .isInstanceOfSatisfying(ProviderAccessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("SEC_RESPONSE_EMPTY");
                    assertThat(exception.retryable()).isFalse();
                });
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsResponseOverTheConfiguredJsonBoundary() throws Exception {
        start(exchange -> {
            capture(exchange);
            respond(exchange, 200, "application/json", "x".repeat(2_048));
        });

        assertThatThrownBy(() -> provider(1, 1_024).fetch("MU"))
                .isInstanceOfSatisfying(ProviderAccessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("SEC_RESPONSE_TOO_LARGE");
                    assertThat(exception.retryable()).isFalse();
                });
        assertThat(userAgents).hasSize(1);
    }

    @Test
    void requiresAnIdentifiedUserAgentWithMonitoredContact() throws Exception {
        start(exchange -> route(exchange, false));
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        SecEdgarProperties properties = new SecEdgarProperties(
                URI.create(base),
                URI.create(base),
                URI.create(base + "/files/company_tickers.json"),
                java.time.Duration.ofSeconds(2),
                "anonymous-client",
                10,
                2,
                100_000,
                100_000,
                1
        );

        assertThatThrownBy(() -> new SecEdgarFilingProvider(
                WebClient.builder(),
                objectMapper,
                properties,
                Clock.systemUTC()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SEC_USER_AGENT");
        assertThat(userAgents).isEmpty();
    }

    private SecEdgarFilingProvider provider(int maxAttempts) {
        return provider(maxAttempts, 100_000);
    }

    private SecEdgarFilingProvider provider(int maxAttempts, int maxJsonBytes) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        SecEdgarProperties properties = new SecEdgarProperties(
                URI.create(base),
                URI.create(base),
                URI.create(base + "/files/company_tickers.json"),
                java.time.Duration.ofSeconds(2),
                USER_AGENT,
                10,
                2,
                maxJsonBytes,
                100_000,
                maxAttempts
        );
        return new SecEdgarFilingProvider(
                WebClient.builder(),
                objectMapper,
                properties,
                Clock.fixed(Instant.parse("2026-07-10T12:00:00Z"), ZoneOffset.UTC)
        );
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handler.handle(exchange));
        server.start();
    }

    private void route(HttpExchange exchange, boolean unsafeDocument) throws IOException {
        capture(exchange);
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/files/company_tickers.json")) {
            respond(exchange, 200, "application/json", """
                    {"0":{"cik_str":123,"ticker":"MU","title":"Micron Test Corp"}}
                    """);
            return;
        }
        if (path.equals("/submissions/CIK0000000123.json")) {
            String primary = unsafeDocument ? "../escape.htm" : "report.htm";
            respond(exchange, 200, "application/json", """
                    {"filings":{"recent":{
                      "accessionNumber":["0000000123-25-000001","0000000123-25-000002"],
                      "form":["10-K","S-8"],
                      "filingDate":["2025-10-03","2025-09-01"],
                      "reportDate":["2025-08-28","2025-08-20"],
                      "primaryDocument":["%s","ignored.htm"]
                    }}}
                    """.formatted(primary));
            return;
        }
        if (path.equals("/Archives/edgar/data/123/000000012325000001/report.htm")) {
            respond(exchange, 200, "text/html", """
                    <html><body><h2>Item 1 Business</h2><p>Registered filing evidence.</p></body></html>
                    """);
            return;
        }
        respond(exchange, 404, "text/plain", "not found");
    }

    private void capture(HttpExchange exchange) {
        userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            String contentType,
            String body
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
