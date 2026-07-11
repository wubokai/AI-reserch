package com.aiquantresearch.api.research.provider.tiingo;

import com.aiquantresearch.api.research.provider.MarketDataProvider;
import com.aiquantresearch.api.research.provider.MarketDataSnapshot;
import com.aiquantresearch.api.research.provider.PriceBar;
import com.aiquantresearch.api.research.provider.ProviderAccessException;
import com.aiquantresearch.api.research.provider.ProviderDataNotFoundException;
import com.aiquantresearch.api.research.provider.runtime.ProviderCall;
import com.aiquantresearch.api.research.provider.runtime.ProviderRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@ConditionalOnProperty(name = "app.providers.market", havingValue = "tiingo")
public class TiingoMarketDataProvider implements MarketDataProvider {

    static final String PROVIDER = "TIINGO_EOD";
    static final String SCHEMA_VERSION = "tiingo_eod_adjusted_v1";
    static final String LICENSE_POLICY_VERSION = "tiingo_individual_internal_v1_2026_02_18";
    static final String ATTRIBUTION = "Data sourced by Tiingo (personal internal use only).";
    private static final String ADJUSTMENT = "SPLIT_AND_CASH_DIVIDEND_ADJUSTED";
    private static final String SYMBOL_PATTERN = "^[A-Z0-9][A-Z0-9.-]{0,31}$";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final TiingoProperties properties;
    private final Clock clock;
    private final ProviderRuntime runtime;

    public TiingoMarketDataProvider(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            TiingoProperties properties,
            Clock clock
    ) {
        this(builder, objectMapper, properties, clock, ProviderRuntime.direct());
    }

    @Autowired
    public TiingoMarketDataProvider(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            TiingoProperties properties,
            Clock clock,
            ProviderRuntime runtime
    ) {
        properties.requireConfiguredAccess();
        this.webClient = builder.clone()
                .codecs(codecs -> codecs.defaultCodecs()
                        .maxInMemorySize(properties.maxResponseBytes()))
                .build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.runtime = runtime;
    }

    @Override
    public MarketDataSnapshot fetchFiveYearDaily(String symbol) {
        String normalized = normalizeSymbol(symbol);
        LocalDate end = LocalDate.now(clock);
        LocalDate start = end.minusYears(5).minusDays(14);
        String subject = normalized + "|" + start + "|" + end + "|" + ADJUSTMENT;
        return runtime.execute(
                new ProviderCall<>(PROVIDER, "tiingo", SCHEMA_VERSION,
                        subject, MarketDataSnapshot.class),
                () -> fetchLive(normalized, start, end)
        );
    }

    private MarketDataSnapshot fetchLive(String symbol, LocalDate start, LocalDate end) {
        URI uri = UriComponentsBuilder.fromUri(properties.baseUrl())
                .pathSegment("tiingo", "daily", symbol, "prices")
                .queryParam("startDate", start)
                .queryParam("endDate", end)
                .queryParam("resampleFreq", "daily")
                .queryParam("format", "json")
                .build(true)
                .toUri();
        byte[] raw = fetchBytes(uri);
        List<PriceBar> prices = parsePrices(raw);
        if (prices.isEmpty()) {
            throw new ProviderDataNotFoundException(
                    "Tiingo returned no adjusted daily prices for the requested security"
            );
        }
        LocalDate first = prices.getFirst().date();
        LocalDate last = prices.getLast().date();
        Instant retrievedAt = clock.instant();
        return new MarketDataSnapshot(
                PROVIDER,
                SCHEMA_VERSION,
                null,
                symbol,
                first,
                last,
                retrievedAt,
                properties.baseUrl().resolve("/tiingo/daily/" + symbol + "/prices").toString(),
                sha256(raw),
                prices,
                ATTRIBUTION,
                null,
                freshness(last, LocalDate.now(clock)),
                false,
                LICENSE_POLICY_VERSION,
                ADJUSTMENT
        );
    }

    private byte[] fetchBytes(URI uri) {
        ProviderAccessException last = null;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                ResponseEntity<byte[]> response = webClient.get()
                        .uri(uri)
                        .header(HttpHeaders.AUTHORIZATION, "Token " + properties.apiKey())
                        .header(HttpHeaders.USER_AGENT, properties.userAgent())
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .toEntity(byte[].class)
                        .block(properties.timeout());
                if (response == null || response.getBody() == null
                        || response.getBody().length == 0) {
                    throw permanent("TIINGO_RESPONSE_EMPTY", "Tiingo returned an empty response");
                }
                MediaType contentType = response.getHeaders().getContentType();
                if (contentType == null
                        || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
                    throw permanent("TIINGO_CONTENT_TYPE_INVALID",
                            "Tiingo returned an unexpected content type");
                }
                if (response.getBody().length > properties.maxResponseBytes()) {
                    throw permanent("TIINGO_RESPONSE_TOO_LARGE",
                            "Tiingo response exceeds the configured byte boundary");
                }
                return response.getBody();
            } catch (DataBufferLimitException exception) {
                throw permanent("TIINGO_RESPONSE_TOO_LARGE",
                        "Tiingo response exceeds the configured byte boundary");
            } catch (WebClientResponseException exception) {
                int status = exception.getStatusCode().value();
                last = new ProviderAccessException(
                        "TIINGO_HTTP_" + status,
                        "Tiingo request failed with HTTP " + status,
                        status == 429 || status == 502 || status == 503
                );
            } catch (WebClientRequestException exception) {
                last = new ProviderAccessException(
                        "TIINGO_UNAVAILABLE", "Tiingo is temporarily unavailable", true);
            } catch (IllegalStateException exception) {
                last = new ProviderAccessException(
                        "TIINGO_TIMEOUT", "Tiingo request exceeded the configured timeout", true);
            }
            if (!last.retryable() || attempt == properties.maxAttempts()) {
                throw last;
            }
            runtime.recordRetry(PROVIDER, last.code());
            java.util.concurrent.locks.LockSupport.parkNanos(
                    100_000_000L * (1L << (attempt - 1))
            );
        }
        throw last == null
                ? permanent("TIINGO_UNAVAILABLE", "Tiingo is unavailable")
                : last;
    }

    private List<PriceBar> parsePrices(byte[] raw) {
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (IOException exception) {
            throw permanent("TIINGO_SCHEMA_INVALID", "Tiingo returned malformed JSON");
        }
        if (root == null || !root.isArray()) {
            throw permanent("TIINGO_SCHEMA_INVALID", "Tiingo returned an invalid JSON root");
        }
        List<PriceBar> result = new ArrayList<>(root.size());
        Set<LocalDate> dates = new HashSet<>();
        for (JsonNode value : root) {
            try {
                String timestamp = requiredText(value, "date");
                LocalDate date = LocalDate.parse(timestamp.substring(0, 10));
                BigDecimal open = positive(value, "adjOpen");
                BigDecimal high = positive(value, "adjHigh");
                BigDecimal low = positive(value, "adjLow");
                BigDecimal close = positive(value, "adjClose");
                long volume = nonNegativeLong(value, "adjVolume");
                if (high.compareTo(open.max(close)) < 0
                        || low.compareTo(open.min(close)) > 0
                        || low.compareTo(high) > 0
                        || !dates.add(date)) {
                    throw new IllegalArgumentException("invalid OHLC relationship or duplicate date");
                }
                result.add(new PriceBar(date, open, high, low, close, close, volume));
            } catch (RuntimeException exception) {
                throw permanent("TIINGO_SCHEMA_INVALID",
                        "Tiingo returned an invalid adjusted daily observation");
            }
        }
        result.sort(Comparator.comparing(PriceBar::date));
        return List.copyOf(result);
    }

    private static BigDecimal positive(JsonNode node, String field) {
        JsonNode value = node.path(field);
        BigDecimal parsed = value.isNumber()
                ? value.decimalValue()
                : new BigDecimal(requiredText(node, field));
        if (parsed.signum() <= 0) {
            throw new IllegalArgumentException("price must be positive");
        }
        return parsed;
    }

    private static long nonNegativeLong(JsonNode node, String field) {
        BigDecimal value = node.path(field).isNumber()
                ? node.path(field).decimalValue()
                : new BigDecimal(requiredText(node, field));
        long parsed = value.longValueExact();
        if (parsed < 0) {
            throw new IllegalArgumentException("volume must be non-negative");
        }
        return parsed;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("missing field");
        }
        return value;
    }

    private static String normalizeSymbol(String symbol) {
        String normalized = symbol == null ? "" : symbol.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches(SYMBOL_PATTERN)) {
            throw permanent("TIINGO_SYMBOL_INVALID", "The requested Tiingo symbol is invalid");
        }
        return normalized.replace('.', '-');
    }

    private static String freshness(LocalDate latest, LocalDate today) {
        long age = java.time.temporal.ChronoUnit.DAYS.between(latest, today);
        if (age <= 4) {
            return "FRESH";
        }
        return age <= 10 ? "STALE" : "VERY_STALE";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static ProviderAccessException permanent(String code, String message) {
        return new ProviderAccessException(code, message, false);
    }
}
