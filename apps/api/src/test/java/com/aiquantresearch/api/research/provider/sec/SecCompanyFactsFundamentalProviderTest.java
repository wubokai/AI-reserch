package com.aiquantresearch.api.research.provider.sec;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiquantresearch.api.research.provider.FundamentalMetric;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class SecCompanyFactsFundamentalProviderTest {

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
    void mapsGoldenAnnualAndInstantFactsWithoutCrossPeriodMixing() throws Exception {
        byte[] golden = getClass().getResourceAsStream(
                "/provider/sec/companyfacts-golden-v1.json"
        ).readAllBytes();
        start(exchange -> route(exchange, golden));

        var snapshot = provider(1).fetch("mu");
        Map<String, FundamentalMetric> metrics = snapshot.metrics().stream()
                .collect(Collectors.toMap(FundamentalMetric::name, Function.identity()));

        assertThat(snapshot.provider()).isEqualTo("SEC_EDGAR_XBRL");
        assertThat(snapshot.schemaVersion()).isEqualTo("sec_companyfacts_normalized_v2");
        assertThat(snapshot.symbol()).isEqualTo("MU");
        assertThat(snapshot.asOfDate()).isEqualTo(LocalDate.parse("2026-05-31"));
        assertThat(snapshot.demoData()).isFalse();
        assertThat(snapshot.rawDataHash()).matches("^[0-9a-f]{64}$");
        assertThat(snapshot.scenarios()).isEmpty();
        assertThat(snapshot.freshnessStatus()).isEqualTo("VERY_STALE");
        assertThat(snapshot.warnings()).contains("MIXED_PERIODS_PRESENT");
        assertThat(metrics).containsOnlyKeys(
                "revenue", "operatingIncome", "netIncome", "dilutedShares",
                "grossMargin", "freeCashFlow", "ebitda", "netDebt"
        );
        assertThat(metrics.get("revenue").value()).isEqualByComparingTo("1100");
        assertThat(metrics.get("revenue").accessionNumber())
                .isEqualTo("0000000123-25-000002");
        assertThat(metrics.get("grossMargin").value()).isEqualByComparingTo("0.4");
        assertThat(metrics.get("freeCashFlow").value()).isEqualByComparingTo("220");
        assertThat(metrics.get("ebitda").value()).isEqualByComparingTo("250");
        assertThat(metrics.get("netDebt").value()).isEqualByComparingTo("300");
        assertThat(metrics.get("netDebt").periodType()).isEqualTo("POINT_IN_TIME");
        assertThat(metrics.get("grossMargin").componentConcepts())
                .containsExactly("GrossProfit",
                        "RevenueFromContractWithCustomerExcludingAssessedTax");
        assertThat(userAgents).containsExactly(USER_AGENT, USER_AGENT);
    }

    @Test
    void refusesToDeriveMetricAcrossDifferentPeriods() throws Exception {
        byte[] payload = """
                {"cik":123,"facts":{"us-gaap":{
                  "RevenueFromContractWithCustomerExcludingAssessedTax":{"units":{"USD":[
                    {"start":"2024-01-01","end":"2024-12-31","val":100,
                     "accn":"0000000123-25-000001","fp":"FY","form":"10-K","filed":"2025-02-01"}
                  ]}},
                  "GrossProfit":{"units":{"USD":[
                    {"start":"2023-01-01","end":"2023-12-31","val":90,
                     "accn":"0000000123-24-000001","fp":"FY","form":"10-K","filed":"2024-02-01"}
                  ]}}
                }}}
                """.getBytes(StandardCharsets.UTF_8);
        start(exchange -> route(exchange, payload));

        var snapshot = provider(1).fetch("MU");

        assertThat(snapshot.metrics()).extracting(FundamentalMetric::name)
                .containsExactly("revenue");
        assertThat(snapshot.warnings()).contains("METRIC_NOT_AVAILABLE:grossMargin");
    }

    @Test
    void retriesTransientRateLimitWithoutChangingGoldenResult() throws Exception {
        byte[] golden = getClass().getResourceAsStream(
                "/provider/sec/companyfacts-golden-v1.json"
        ).readAllBytes();
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/files/company_tickers.json")
                    && calls.getAndIncrement() == 0) {
                capture(exchange);
                respond(exchange, 429, "application/json", "{\"error\":\"limited\"}");
                return;
            }
            route(exchange, golden);
        });

        assertThat(provider(2).fetch("MU").metrics()).hasSize(8);
        assertThat(calls).hasValue(2);
    }

    @Test
    void derivesNetDebtFromCurrentAndNoncurrentDebtWhenAggregateTagIsAbsent()
            throws Exception {
        byte[] payload = """
                {"cik":123,"facts":{"us-gaap":{
                  "LongTermDebt":{"units":{"USD":[
                    {"end":"2023-12-31","val":1000,"accn":"0000000123-24-000001",
                     "fp":"FY","form":"10-K","filed":"2024-02-01"}
                  ]}},
                  "LongTermDebtCurrent":{"units":{"USD":[
                    {"end":"2026-03-31","val":10,"accn":"0000000123-26-000001",
                     "fp":"Q1","form":"10-Q","filed":"2026-05-01"}
                  ]}},
                  "LongTermDebtNoncurrent":{"units":{"USD":[
                    {"end":"2026-03-31","val":90,"accn":"0000000123-26-000001",
                     "fp":"Q1","form":"10-Q","filed":"2026-05-01"}
                  ]}},
                  "CashAndCashEquivalentsAtCarryingValue":{"units":{"USD":[
                    {"end":"2026-03-31","val":40,"accn":"0000000123-26-000001",
                     "fp":"Q1","form":"10-Q","filed":"2026-05-01"}
                  ]}}
                }}}
                """.getBytes(StandardCharsets.UTF_8);
        start(exchange -> route(exchange, payload));

        var snapshot = provider(1).fetch("MU");
        FundamentalMetric netDebt = snapshot.metrics().stream()
                .filter(metric -> "netDebt".equals(metric.name()))
                .findFirst()
                .orElseThrow();

        assertThat(netDebt.value()).isEqualByComparingTo("60");
        assertThat(netDebt.componentConcepts()).containsExactly(
                "LongTermDebtCurrent",
                "LongTermDebtNoncurrent",
                "CashAndCashEquivalentsAtCarryingValue"
        );
    }

    private SecCompanyFactsFundamentalProvider provider(int maxAttempts) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        SecEdgarProperties properties = new SecEdgarProperties(
                URI.create(base),
                URI.create(base),
                URI.create(base + "/files/company_tickers.json"),
                URI.create(base + "/files/company_tickers_exchange.json"),
                Duration.ofSeconds(2),
                USER_AGENT,
                10,
                2,
                1_000_000,
                1_000_000,
                maxAttempts
        );
        return new SecCompanyFactsFundamentalProvider(
                WebClient.builder(),
                objectMapper,
                properties,
                Clock.fixed(Instant.parse("2026-07-11T12:00:00Z"), ZoneOffset.UTC)
        );
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler::handle);
        server.start();
    }

    private void route(HttpExchange exchange, byte[] companyFacts) throws IOException {
        capture(exchange);
        if (exchange.getRequestURI().getPath().equals("/files/company_tickers.json")) {
            respond(exchange, 200, "application/json",
                    "{\"0\":{\"cik_str\":123,\"ticker\":\"MU\",\"title\":\"Micron Golden Corp\"}}");
            return;
        }
        if (exchange.getRequestURI().getPath()
                .equals("/api/xbrl/companyfacts/CIK0000000123.json")) {
            respond(exchange, 200, "application/json", companyFacts);
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
        respond(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            String contentType,
            byte[] bytes
    ) throws IOException {
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
