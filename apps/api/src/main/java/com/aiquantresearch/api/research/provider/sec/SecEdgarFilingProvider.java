package com.aiquantresearch.api.research.provider.sec;

import com.aiquantresearch.api.research.provider.FilingDocument;
import com.aiquantresearch.api.research.provider.FilingProvider;
import com.aiquantresearch.api.research.provider.FilingSnapshot;
import com.aiquantresearch.api.research.provider.ProviderAccessException;
import com.aiquantresearch.api.research.provider.ProviderDataNotFoundException;
import com.aiquantresearch.api.research.provider.runtime.ProviderCall;
import com.aiquantresearch.api.research.provider.runtime.ProviderRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
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
@ConditionalOnProperty(name = "app.providers.filing", havingValue = "sec")
public class SecEdgarFilingProvider implements FilingProvider {

    static final String PROVIDER = "SEC_EDGAR";
    static final String SCHEMA_VERSION = "sec_filings_v1";
    static final String LICENSE_POLICY_VERSION = "sec_public_edgar_2025_04_08";

    private static final Pattern ACCESSION = Pattern.compile("^[0-9]{10}-[0-9]{2}-[0-9]{6}$");
    private static final Pattern SAFE_DOCUMENT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,254}$");
    private static final Set<String> SUPPORTED_FORMS = Set.of(
            "10-K", "10-K/A", "10-Q", "10-Q/A", "8-K", "8-K/A",
            "20-F", "20-F/A", "40-F", "40-F/A", "6-K", "6-K/A"
    );

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final SecEdgarProperties properties;
    private final SecRequestGovernor governor;
    private final Clock clock;
    private final ProviderRuntime runtime;

    public SecEdgarFilingProvider(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            SecEdgarProperties properties,
            Clock clock
    ) {
        this(builder, objectMapper, properties, clock, ProviderRuntime.direct());
    }

    @Autowired
    public SecEdgarFilingProvider(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            SecEdgarProperties properties,
            Clock clock,
            ProviderRuntime runtime
    ) {
        properties.requireConfiguredIdentity();
        validateEndpoint(properties.dataBaseUrl(), "data.sec.gov");
        validateEndpoint(properties.archivesBaseUrl(), "www.sec.gov");
        validateEndpoint(properties.companyTickersUrl(), "www.sec.gov");
        this.webClient = builder.clone()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(
                        Math.max(properties.maxJsonBytes(), properties.maxFilingBytes())
                ))
                .build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.governor = new SecRequestGovernor(properties.maxRequestsPerSecond());
        this.clock = clock;
        this.runtime = runtime;
    }

    @Override
    public FilingSnapshot fetch(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        return runtime.execute(
                new ProviderCall<>(
                        PROVIDER, "secEdgar", SCHEMA_VERSION,
                        normalizedSymbol + "|maxFilings=" + properties.maxFilings(),
                        FilingSnapshot.class
                ),
                () -> fetchLive(normalizedSymbol)
        );
    }

    private FilingSnapshot fetchLive(String normalizedSymbol) {
        List<byte[]> rawParts = new ArrayList<>();
        byte[] tickersBytes = fetchBytes(
                properties.companyTickersUrl(),
                MediaType.APPLICATION_JSON,
                properties.maxJsonBytes()
        );
        rawParts.add(tickersBytes);
        CompanyIdentity company = company(parseJson(tickersBytes, "SEC_TICKERS_SCHEMA_INVALID"),
                normalizedSymbol);

        URI submissionsUri = UriComponentsBuilder.fromUri(properties.dataBaseUrl())
                .pathSegment("submissions", "CIK%010d.json".formatted(company.cik()))
                .build(true)
                .toUri();
        byte[] submissionsBytes = fetchBytes(
                submissionsUri,
                MediaType.APPLICATION_JSON,
                properties.maxJsonBytes()
        );
        rawParts.add(submissionsBytes);
        JsonNode submissions = parseJson(submissionsBytes, "SEC_SUBMISSIONS_SCHEMA_INVALID");
        List<FilingDocument> filings = filings(company, submissions, rawParts);
        Instant retrievedAt = clock.instant();
        LocalDate asOfDate = filings.stream()
                .map(FilingDocument::filingDate)
                .max(LocalDate::compareTo)
                .orElse(LocalDate.now(clock));

        return new FilingSnapshot(
                PROVIDER,
                SCHEMA_VERSION,
                null,
                normalizedSymbol,
                asOfDate,
                retrievedAt,
                submissionsUri.toString(),
                combinedHash(rawParts),
                filings,
                null,
                false,
                freshness(asOfDate, LocalDate.now(clock)),
                LICENSE_POLICY_VERSION
        );
    }

    private List<FilingDocument> filings(
            CompanyIdentity company,
            JsonNode submissions,
            List<byte[]> rawParts
    ) {
        JsonNode recent = submissions.path("filings").path("recent");
        JsonNode accessions = requiredArray(recent, "accessionNumber");
        JsonNode forms = requiredArray(recent, "form");
        JsonNode filingDates = requiredArray(recent, "filingDate");
        JsonNode reportDates = requiredArray(recent, "reportDate");
        JsonNode primaryDocuments = requiredArray(recent, "primaryDocument");
        int size = accessions.size();
        if (forms.size() != size || filingDates.size() != size
                || reportDates.size() != size || primaryDocuments.size() != size) {
            throw permanent("SEC_SUBMISSIONS_SCHEMA_INVALID",
                    "SEC submissions arrays do not share a stable index boundary");
        }

        List<FilingDocument> result = new ArrayList<>();
        for (int index = 0; index < size && result.size() < properties.maxFilings(); index++) {
            String form = forms.get(index).asText();
            if (!SUPPORTED_FORMS.contains(form)) {
                continue;
            }
            String accession = accessions.get(index).asText();
            String primaryDocument = primaryDocuments.get(index).asText();
            if (!ACCESSION.matcher(accession).matches()
                    || !SAFE_DOCUMENT.matcher(primaryDocument).matches()) {
                throw permanent("SEC_DOCUMENT_ID_INVALID",
                        "SEC returned an unsafe filing document identifier");
            }
            URI documentUri = UriComponentsBuilder.fromUri(properties.archivesBaseUrl())
                    .pathSegment(
                            "Archives",
                            "edgar",
                            "data",
                            Integer.toString(company.cik()),
                            accession.replace("-", ""),
                            primaryDocument
                    )
                    .build(true)
                    .toUri();
            byte[] htmlBytes = fetchBytes(
                    documentUri,
                    MediaType.TEXT_HTML,
                    properties.maxFilingBytes()
            );
            rawParts.add(htmlBytes);
            String html = new String(htmlBytes, StandardCharsets.UTF_8);
            LocalDate filingDate = parseDate(filingDates.get(index).asText(), true);
            LocalDate reportPeriod = parseDate(reportDates.get(index).asText(), false);
            String title = form + " — " + company.name();
            result.add(new FilingDocument(
                    accession + ":" + primaryDocument,
                    accession,
                    form,
                    filingDate,
                    reportPeriod,
                    title,
                    summary(html),
                    documentUri.toString(),
                    html
            ));
        }
        return List.copyOf(result);
    }

    private byte[] fetchBytes(URI uri, MediaType expectedType, int maximumBytes) {
        ProviderAccessException last = null;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            governor.acquire();
            try {
                ResponseEntity<byte[]> response = webClient.get()
                        .uri(uri)
                        .header(HttpHeaders.USER_AGENT, properties.userAgent())
                        .header(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate")
                        .accept(expectedType, MediaType.APPLICATION_OCTET_STREAM)
                        .retrieve()
                        .toEntity(byte[].class)
                        .block(properties.timeout());
                if (response == null) {
                    throw permanent("SEC_RESPONSE_EMPTY", "SEC returned an empty response");
                }
                validateContentType(expectedType, response.getHeaders().getContentType());
                byte[] body = response.getBody();
                if (body == null || body.length == 0) {
                    throw permanent("SEC_RESPONSE_EMPTY", "SEC returned an empty response");
                }
                if (body.length > maximumBytes) {
                    throw permanent("SEC_RESPONSE_TOO_LARGE",
                            "SEC response exceeds the configured byte boundary");
                }
                return body;
            } catch (DataBufferLimitException exception) {
                throw new ProviderAccessException(
                        "SEC_RESPONSE_TOO_LARGE",
                        "SEC response exceeds the configured byte boundary",
                        false,
                        exception
                );
            } catch (WebClientResponseException exception) {
                int status = exception.getStatusCode().value();
                boolean retryable = status == 429 || status == 502 || status == 503;
                last = new ProviderAccessException(
                        "SEC_HTTP_" + status,
                        "SEC request failed with HTTP " + status,
                        retryable,
                        exception
                );
            } catch (WebClientRequestException exception) {
                last = new ProviderAccessException(
                        "SEC_UNAVAILABLE",
                        "SEC is temporarily unavailable",
                        true,
                        exception
                );
            } catch (IllegalStateException exception) {
                last = new ProviderAccessException(
                        "SEC_TIMEOUT",
                        "SEC request exceeded the configured timeout",
                        true,
                        exception
                );
            }
            if (!last.retryable() || attempt == properties.maxAttempts()) {
                throw last;
            }
            runtime.recordRetry(PROVIDER, last.code());
            java.util.concurrent.locks.LockSupport.parkNanos(
                    100_000_000L * (1L << (attempt - 1))
            );
        }
        throw last == null ? permanent("SEC_UNAVAILABLE", "SEC is unavailable") : last;
    }

    private static void validateContentType(MediaType expected, MediaType actual) {
        boolean octetStream = actual != null
                && MediaType.APPLICATION_OCTET_STREAM.isCompatibleWith(actual);
        boolean htmlAsPlainText = MediaType.TEXT_HTML.equals(expected)
                && actual != null
                && MediaType.TEXT_PLAIN.isCompatibleWith(actual);
        if (actual == null
                || (!expected.isCompatibleWith(actual) && !octetStream && !htmlAsPlainText)) {
            throw permanent(
                    "SEC_CONTENT_TYPE_INVALID",
                    "SEC returned an unexpected content type"
            );
        }
    }

    private CompanyIdentity company(JsonNode root, String symbol) {
        for (JsonNode candidate : root) {
            if (symbol.equalsIgnoreCase(candidate.path("ticker").asText())) {
                int cik = candidate.path("cik_str").asInt(0);
                String name = candidate.path("title").asText();
                if (cik < 1 || name.isBlank()) {
                    throw permanent("SEC_TICKERS_SCHEMA_INVALID",
                            "SEC ticker metadata is incomplete");
                }
                return new CompanyIdentity(cik, name);
            }
        }
        throw new ProviderDataNotFoundException(symbol);
    }

    private JsonNode parseJson(byte[] bytes, String code) {
        try {
            JsonNode root = objectMapper.readTree(bytes);
            if (root == null || !root.isObject()) {
                throw permanent(code, "SEC returned an invalid JSON root");
            }
            return root;
        } catch (IOException exception) {
            throw new ProviderAccessException(
                    code,
                    "SEC returned malformed JSON",
                    false,
                    exception
            );
        }
    }

    private static JsonNode requiredArray(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isArray()) {
            throw permanent("SEC_SUBMISSIONS_SCHEMA_INVALID",
                    "SEC submissions response is missing a required array");
        }
        return value;
    }

    private static LocalDate parseDate(String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (!required) {
                return null;
            }
            throw permanent("SEC_SUBMISSIONS_SCHEMA_INVALID", "SEC filing date is missing");
        }
        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException exception) {
            throw permanent("SEC_SUBMISSIONS_SCHEMA_INVALID", "SEC returned an invalid date");
        }
    }

    private static String normalizeSymbol(String symbol) {
        String value = symbol == null ? "" : symbol.strip().toUpperCase(Locale.ROOT);
        if (!value.matches("^[A-Z0-9][A-Z0-9.-]{0,31}$")) {
            throw permanent("SEC_SYMBOL_INVALID", "The SEC symbol is invalid");
        }
        return value;
    }

    private static String summary(String html) {
        String text = Jsoup.parse(html).text().replaceAll("\\s+", " ").strip();
        return text.length() <= 1_200 ? text : text.substring(0, 1_200);
    }

    private static String freshness(LocalDate latestFiling, LocalDate asOfDate) {
        long age = java.time.temporal.ChronoUnit.DAYS.between(latestFiling, asOfDate);
        if (age <= 120) {
            return "FRESH";
        }
        if (age <= 180) {
            return "STALE";
        }
        return "VERY_STALE";
    }

    private static String combinedHash(List<byte[]> parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(part.length).array());
                digest.update(part);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static void validateEndpoint(URI uri, String officialHost) {
        String host = uri.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        if (host == null
                || (!loopback && !"https".equalsIgnoreCase(uri.getScheme()))
                || (!loopback && !officialHost.equalsIgnoreCase(host))
                || uri.getUserInfo() != null) {
            throw new IllegalStateException("SEC endpoint is outside the approved host boundary");
        }
    }

    private static ProviderAccessException permanent(String code, String message) {
        return new ProviderAccessException(code, message, false);
    }

    private record CompanyIdentity(int cik, String name) {
    }
}
