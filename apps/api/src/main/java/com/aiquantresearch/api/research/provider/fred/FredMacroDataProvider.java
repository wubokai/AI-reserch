package com.aiquantresearch.api.research.provider.fred;

import com.aiquantresearch.api.research.provider.MacroDataProvider;
import com.aiquantresearch.api.research.provider.MacroDataSnapshot;
import com.aiquantresearch.api.research.provider.MacroObservation;
import com.aiquantresearch.api.research.provider.MacroSeries;
import com.aiquantresearch.api.research.provider.ProviderAccessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
@ConditionalOnProperty(name = "app.providers.macro", havingValue = "fred")
public class FredMacroDataProvider implements MacroDataProvider {

    static final String PROVIDER = "FRED";
    static final String SCHEMA_VERSION = "fred_macro_v1";
    static final String LICENSE_POLICY_VERSION = "fred_api_terms_reviewed_2026_07_10";
    static final String ATTRIBUTION = "This product uses the FRED® API but is not endorsed or "
            + "certified by the Federal Reserve Bank of St. Louis.";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final FredProperties properties;
    private final FredRequestGovernor governor;
    private final Clock clock;

    public FredMacroDataProvider(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            FredProperties properties,
            Clock clock
    ) {
        properties.requireConfiguredAccess();
        validateEndpoint(properties.baseUrl());
        webClient = builder.clone()
                .codecs(codecs -> codecs.defaultCodecs()
                        .maxInMemorySize(properties.maxResponseBytes()))
                .build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        governor = new FredRequestGovernor(properties.maxRequestsPerSecond());
        this.clock = clock;
    }

    @Override
    public MacroDataSnapshot fetch() {
        LocalDate vintageDate = LocalDate.now(clock);
        List<byte[]> rawParts = new ArrayList<>();
        List<MacroSeries> series = properties.seriesIds().stream()
                .map(seriesId -> series(seriesId, vintageDate, rawParts))
                .toList();
        LocalDate effectiveDate = series.stream()
                .flatMap(value -> value.observations().stream())
                .map(MacroObservation::date)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> permanent("FRED_SERIES_EMPTY",
                        "FRED returned no usable observations"));
        Instant retrievedAt = clock.instant();
        return new MacroDataSnapshot(
                PROVIDER,
                SCHEMA_VERSION,
                null,
                effectiveDate,
                vintageDate,
                retrievedAt,
                properties.baseUrl().resolve("/fred/").toString(),
                combinedHash(rawParts),
                series,
                ATTRIBUTION,
                null,
                false,
                freshness(series, vintageDate),
                LICENSE_POLICY_VERSION
        );
    }

    private MacroSeries series(String seriesId, LocalDate vintageDate, List<byte[]> rawParts) {
        URI metadataUri = uri("/fred/series", seriesId, vintageDate, false);
        byte[] metadataBytes = fetchBytes(metadataUri);
        rawParts.add(metadataBytes);
        JsonNode metadataRoot = parseJson(metadataBytes, "FRED_SERIES_SCHEMA_INVALID");
        JsonNode metadata = metadataRoot.path("seriess");
        if (!metadata.isArray() || metadata.size() != 1) {
            throw permanent("FRED_SERIES_SCHEMA_INVALID",
                    "FRED series metadata does not contain exactly one series");
        }
        JsonNode item = metadata.get(0);
        if (!seriesId.equalsIgnoreCase(requiredText(item, "id"))) {
            throw permanent("FRED_SERIES_SCHEMA_INVALID",
                    "FRED returned metadata for an unexpected series");
        }

        URI observationsUri = uri("/fred/series/observations", seriesId, vintageDate, true);
        byte[] observationBytes = fetchBytes(observationsUri);
        rawParts.add(observationBytes);
        JsonNode observationRoot = parseJson(observationBytes, "FRED_OBSERVATIONS_SCHEMA_INVALID");
        JsonNode values = observationRoot.path("observations");
        if (!values.isArray()) {
            throw permanent("FRED_OBSERVATIONS_SCHEMA_INVALID",
                    "FRED observations are missing");
        }
        int count = observationRoot.path("count").asInt(values.size());
        if (count > values.size()) {
            throw permanent("FRED_RESPONSE_TRUNCATED",
                    "FRED observations exceed the configured retrieval boundary");
        }
        List<MacroObservation> observations = new ArrayList<>();
        for (JsonNode value : values) {
            String numeric = requiredText(value, "value");
            if (".".equals(numeric)) {
                continue;
            }
            try {
                observations.add(new MacroObservation(
                        LocalDate.parse(requiredText(value, "date")),
                        new BigDecimal(numeric),
                        LocalDate.parse(requiredText(value, "realtime_start")),
                        LocalDate.parse(requiredText(value, "realtime_end"))
                ));
            } catch (NumberFormatException | DateTimeParseException exception) {
                throw permanent("FRED_OBSERVATIONS_SCHEMA_INVALID",
                        "FRED returned an invalid observation");
            }
        }
        if (observations.isEmpty()) {
            throw permanent("FRED_SERIES_EMPTY",
                    "FRED returned no usable observations for the configured series");
        }
        return new MacroSeries(
                seriesId.toUpperCase(Locale.ROOT),
                requiredText(item, "title"),
                requiredText(item, "units"),
                requiredText(item, "frequency"),
                requiredText(item, "frequency_short"),
                requiredText(item, "units_short"),
                requiredText(item, "seasonal_adjustment"),
                requiredText(item, "last_updated"),
                List.copyOf(observations)
        );
    }

    private URI uri(String path, String seriesId, LocalDate vintageDate, boolean observations) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(properties.baseUrl())
                .path(path)
                .queryParam("series_id", seriesId)
                .queryParam("api_key", properties.apiKey())
                .queryParam("file_type", "json")
                .queryParam("realtime_start", vintageDate)
                .queryParam("realtime_end", vintageDate);
        if (observations) {
            builder.queryParam("observation_start", properties.observationStart())
                    .queryParam("observation_end", vintageDate)
                    .queryParam("sort_order", "asc")
                    .queryParam("limit", properties.maxObservations());
        }
        return builder.build(true).toUri();
    }

    private byte[] fetchBytes(URI uri) {
        ProviderAccessException last = null;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            governor.acquire();
            try {
                ResponseEntity<byte[]> response = webClient.get()
                        .uri(uri)
                        .header(HttpHeaders.USER_AGENT, properties.userAgent())
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .toEntity(byte[].class)
                        .block(properties.timeout());
                if (response == null || response.getBody() == null
                        || response.getBody().length == 0) {
                    throw permanent("FRED_RESPONSE_EMPTY", "FRED returned an empty response");
                }
                MediaType contentType = response.getHeaders().getContentType();
                if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
                    throw permanent("FRED_CONTENT_TYPE_INVALID",
                            "FRED returned an unexpected content type");
                }
                if (response.getBody().length > properties.maxResponseBytes()) {
                    throw permanent("FRED_RESPONSE_TOO_LARGE",
                            "FRED response exceeds the configured byte boundary");
                }
                return response.getBody();
            } catch (DataBufferLimitException exception) {
                throw permanent("FRED_RESPONSE_TOO_LARGE",
                        "FRED response exceeds the configured byte boundary");
            } catch (WebClientResponseException exception) {
                int status = exception.getStatusCode().value();
                last = new ProviderAccessException(
                        "FRED_HTTP_" + status,
                        "FRED request failed with HTTP " + status,
                        status == 429 || status == 502 || status == 503
                );
            } catch (WebClientRequestException exception) {
                last = new ProviderAccessException(
                        "FRED_UNAVAILABLE", "FRED is temporarily unavailable", true);
            } catch (IllegalStateException exception) {
                last = new ProviderAccessException(
                        "FRED_TIMEOUT", "FRED request exceeded the configured timeout", true);
            }
            if (!last.retryable() || attempt == properties.maxAttempts()) {
                throw last;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(
                    100_000_000L * (1L << (attempt - 1))
            );
        }
        throw last == null ? permanent("FRED_UNAVAILABLE", "FRED is unavailable") : last;
    }

    private JsonNode parseJson(byte[] bytes, String code) {
        try {
            JsonNode root = objectMapper.readTree(bytes);
            if (root == null || !root.isObject()) {
                throw permanent(code, "FRED returned an invalid JSON root");
            }
            return root;
        } catch (IOException exception) {
            throw permanent(code, "FRED returned malformed JSON");
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw permanent("FRED_SCHEMA_INVALID", "FRED response is missing required metadata");
        }
        return value;
    }

    private static String freshness(List<MacroSeries> series, LocalDate vintageDate) {
        int worst = series.stream().mapToInt(value -> {
            LocalDate latest = value.observations().getLast().date();
            long age = java.time.temporal.ChronoUnit.DAYS.between(latest, vintageDate);
            int freshDays = switch (value.frequencyShort().toUpperCase(Locale.ROOT)) {
                case "D" -> 7;
                case "W", "BW" -> 21;
                case "M" -> 62;
                case "Q", "SA" -> 185;
                case "A" -> 550;
                default -> -1;
            };
            if (freshDays < 0) {
                return 3;
            }
            if (age <= freshDays) {
                return 0;
            }
            return age <= Math.round(freshDays * 1.5) ? 1 : 2;
        }).max().orElse(3);
        return switch (worst) {
            case 0 -> "FRESH";
            case 1 -> "STALE";
            case 2 -> "VERY_STALE";
            default -> "UNKNOWN";
        };
    }

    private static String combinedHash(List<byte[]> parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(part.length).array());
                digest.update(part);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static void validateEndpoint(URI uri) {
        String host = uri.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        if (host == null
                || (!loopback && !"https".equalsIgnoreCase(uri.getScheme()))
                || (!loopback && !"api.stlouisfed.org".equalsIgnoreCase(host))
                || uri.getUserInfo() != null) {
            throw new IllegalStateException("FRED endpoint is outside the approved host boundary");
        }
    }

    private static ProviderAccessException permanent(String code, String message) {
        return new ProviderAccessException(code, message, false);
    }
}
