package com.aiquantresearch.api.research.provider.tiingo;

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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class TiingoMarketDataProviderTest {

    private static final String API_KEY = "tiingo_test_token_1234567890";
    private final List<String> authorization = new ArrayList<>();
    private final List<String> queries = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsAdjustedOhlcvAndKeepsTokenOutOfLineage() throws Exception {
        start(exchange -> respond(exchange, 200, "application/json", prices()));

        var snapshot = provider(1).fetchFiveYearDaily("mu");

        assertThat(snapshot.provider()).isEqualTo("TIINGO_EOD");
        assertThat(snapshot.schemaVersion()).isEqualTo("tiingo_eod_adjusted_v1");
        assertThat(snapshot.symbol()).isEqualTo("MU");
        assertThat(snapshot.periodStart()).isEqualTo(LocalDate.parse("2026-07-09"));
        assertThat(snapshot.periodEnd()).isEqualTo(LocalDate.parse("2026-07-10"));
        assertThat(snapshot.rawDataHash()).matches("^[0-9a-f]{64}$");
        assertThat(snapshot.sourceUrl()).doesNotContain(API_KEY);
        assertThat(snapshot.demoData()).isFalse();
        assertThat(snapshot.attribution()).contains("Tiingo");
        assertThat(snapshot.priceAdjustment()).contains("DIVIDEND");
        assertThat(snapshot.prices()).hasSize(2);
        assertThat(snapshot.prices().getFirst()).satisfies(bar -> {
            assertThat(bar.open()).isEqualByComparingTo("119.0");
            assertThat(bar.high()).isEqualByComparingTo("122.0");
            assertThat(bar.low()).isEqualByComparingTo("118.0");
            assertThat(bar.close()).isEqualByComparingTo("121.0");
            assertThat(bar.adjustedClose()).isEqualByComparingTo("121.0");
            assertThat(bar.volume()).isEqualTo(1_100_000L);
        });
        assertThat(authorization).containsExactly("Token " + API_KEY);
        assertThat(queries).singleElement().satisfies(query -> {
            assertThat(query).contains("startDate=2021-06-26");
            assertThat(query).contains("endDate=2026-07-10");
            assertThat(query).doesNotContain(API_KEY);
        });
    }

    @Test
    void retriesOnlyTransientHttpFailuresWithoutLeakingBodyOrToken() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> {
            if (calls.getAndIncrement() == 0) {
                respond(exchange, 429, "application/json",
                        "{\"token\":\"" + API_KEY + "\",\"secret\":\"hidden\"}");
                return;
            }
            respond(exchange, 200, "application/json", prices());
        });

        assertThat(provider(2).fetchFiveYearDaily("MU").prices()).hasSize(2);
        assertThat(calls).hasValue(2);
    }

    @Test
    void rejectsInvalidAdjustedObservation() throws Exception {
        start(exchange -> respond(exchange, 200, "application/json", """
                [{"date":"2026-07-10T00:00:00.000Z","adjOpen":120,
                  "adjHigh":100,"adjLow":119,"adjClose":121,"adjVolume":1000}]
                """));

        assertThatThrownBy(() -> provider(1).fetchFiveYearDaily("MU"))
                .isInstanceOfSatisfying(ProviderAccessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("TIINGO_SCHEMA_INVALID");
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.getMessage()).doesNotContain(API_KEY);
                });
    }

    @Test
    void allowsOnlyOfficialOrLoopbackEndpoints() {
        assertThatThrownBy(() -> TiingoProperties.validateEndpoint(
                URI.create("https://api.tiingo.com.attacker.invalid")
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TiingoProperties.validateEndpoint(
                URI.create("http://169.254.169.254/latest/meta-data")
        )).isInstanceOf(IllegalStateException.class);
    }

    private TiingoMarketDataProvider provider(int maxAttempts) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        TiingoProperties properties = new TiingoProperties(
                URI.create(base),
                Duration.ofSeconds(2),
                API_KEY,
                "AI Quant Research Assistant tests",
                100_000,
                maxAttempts
        );
        return new TiingoMarketDataProvider(
                WebClient.builder(),
                new ObjectMapper().findAndRegisterModules(),
                properties,
                Clock.fixed(Instant.parse("2026-07-10T12:00:00Z"), ZoneOffset.UTC)
        );
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            queries.add(exchange.getRequestURI().getRawQuery());
            handler.handle(exchange);
        });
        server.start();
    }

    private static String prices() {
        return """
                [
                  {"date":"2026-07-10T00:00:00.000Z","open":130,"high":131,
                   "low":128,"close":129,"volume":900000,"adjOpen":129,
                   "adjHigh":130,"adjLow":127,"adjClose":128,"adjVolume":950000,
                   "divCash":0,"splitFactor":1},
                  {"date":"2026-07-09T00:00:00.000Z","open":120,"high":123,
                   "low":117,"close":122,"volume":1000000,"adjOpen":119,
                   "adjHigh":122,"adjLow":118,"adjClose":121,"adjVolume":1100000,
                   "divCash":0.2,"splitFactor":1}
                ]
                """;
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
