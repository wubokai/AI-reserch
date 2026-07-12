package com.aiquantresearch.api.research.provider.fred;

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

class FredMacroDataProviderTest {

    private static final String API_KEY = "a".repeat(32);

    private final List<String> queries = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsVintageMetadataAndObservationsWithRequiredAttribution() throws Exception {
        start(this::route);

        var snapshot = provider(1).fetch();

        assertThat(snapshot.provider()).isEqualTo("FRED");
        assertThat(snapshot.vintageDate()).isEqualTo(LocalDate.parse("2026-07-10"));
        assertThat(snapshot.asOfDate()).isEqualTo(LocalDate.parse("2026-07-09"));
        assertThat(snapshot.demoData()).isFalse();
        assertThat(snapshot.attribution()).contains("FRED® API");
        assertThat(snapshot.rawDataHash()).matches("^[0-9a-f]{64}$");
        assertThat(snapshot.sourceUrl()).doesNotContain(API_KEY);
        assertThat(snapshot.series()).singleElement().satisfies(series -> {
            assertThat(series.seriesId()).isEqualTo("DFF");
            assertThat(series.frequencyShort()).isEqualTo("D");
            assertThat(series.observations()).singleElement().satisfies(value -> {
                assertThat(value.value()).isEqualByComparingTo("5.33");
                assertThat(value.realtimeStart()).isEqualTo(LocalDate.parse("2026-07-10"));
            });
        });
        assertThat(queries).hasSize(2).allSatisfy(query -> {
            assertThat(query).contains("api_key=" + API_KEY);
            assertThat(query).contains("realtime_start=2026-07-10");
            assertThat(query).contains("realtime_end=2026-07-10");
        });
    }

    @Test
    void retriesRateLimitWithinAttemptBudget() throws Exception {
        AtomicInteger metadataCalls = new AtomicInteger();
        start(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/fred/series")
                    && metadataCalls.getAndIncrement() == 0) {
                capture(exchange);
                respond(exchange, 429, "application/json", "{\"error\":\"limited\"}");
                return;
            }
            route(exchange);
        });

        assertThat(provider(2).fetch().series()).hasSize(1);
        assertThat(metadataCalls).hasValue(2);
    }

    @Test
    void usesFredCentralDateAcrossUtcMidnight() throws Exception {
        start(this::route);

        Clock afterUtcMidnight = Clock.fixed(
                Instant.parse("2026-07-12T00:30:00Z"), ZoneOffset.UTC
        );
        var snapshot = provider(1, afterUtcMidnight).fetch();

        assertThat(snapshot.vintageDate()).isEqualTo(LocalDate.parse("2026-07-11"));
        assertThat(queries).hasSize(2).allSatisfy(query -> {
            assertThat(query).contains("realtime_start=2026-07-11");
            assertThat(query).contains("realtime_end=2026-07-11");
        });
    }

    @Test
    void httpFailureNeverExposesApiKeyOrResponseBody() throws Exception {
        start(exchange -> respond(
                exchange,
                401,
                "application/json",
                "{\"api_key\":\"" + API_KEY + "\",\"secret\":\"do-not-log\"}"
        ));

        assertThatThrownBy(() -> provider(1).fetch())
                .isInstanceOfSatisfying(ProviderAccessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("FRED_HTTP_401");
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.getMessage())
                            .doesNotContain(API_KEY)
                            .doesNotContain("do-not-log");
                    assertThat(exception.getCause()).isNull();
                });
    }

    private FredMacroDataProvider provider(int maxAttempts) {
        return provider(
                maxAttempts,
                Clock.fixed(Instant.parse("2026-07-10T12:00:00Z"), ZoneOffset.UTC)
        );
    }

    private FredMacroDataProvider provider(int maxAttempts, Clock clock) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        FredProperties properties = new FredProperties(
                URI.create(base),
                Duration.ofSeconds(2),
                API_KEY,
                "AI Quant Research Assistant tests",
                List.of("DFF"),
                LocalDate.parse("2026-07-01"),
                100,
                100_000,
                10,
                maxAttempts
        );
        return new FredMacroDataProvider(
                WebClient.builder(),
                new ObjectMapper().findAndRegisterModules(),
                properties,
                clock
        );
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler::handle);
        server.start();
    }

    private void route(HttpExchange exchange) throws IOException {
        capture(exchange);
        if (exchange.getRequestURI().getPath().equals("/fred/series")) {
            respond(exchange, 200, "application/json", """
                    {"seriess":[{"id":"DFF","title":"Federal Funds Effective Rate",
                      "frequency":"Daily","frequency_short":"D","units":"Percent",
                      "units_short":"Percent","seasonal_adjustment":"Not Seasonally Adjusted",
                      "last_updated":"2026-07-10 15:17:01-05"}]}
                    """);
            return;
        }
        if (exchange.getRequestURI().getPath().equals("/fred/series/observations")) {
            respond(exchange, 200, "application/json", """
                    {"count":2,"observations":[
                      {"realtime_start":"2026-07-10","realtime_end":"2026-07-10",
                       "date":"2026-07-08","value":"."},
                      {"realtime_start":"2026-07-10","realtime_end":"2026-07-10",
                       "date":"2026-07-09","value":"5.33"}]}
                    """);
            return;
        }
        respond(exchange, 404, "text/plain", "not found");
    }

    private void capture(HttpExchange exchange) {
        queries.add(exchange.getRequestURI().getRawQuery());
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
