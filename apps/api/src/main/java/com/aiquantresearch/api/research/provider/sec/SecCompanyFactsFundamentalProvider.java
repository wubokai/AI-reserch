package com.aiquantresearch.api.research.provider.sec;

import com.aiquantresearch.api.research.provider.FundamentalDataProvider;
import com.aiquantresearch.api.research.provider.FundamentalDataSnapshot;
import com.aiquantresearch.api.research.provider.FundamentalMetric;
import com.aiquantresearch.api.research.provider.ProviderAccessException;
import com.aiquantresearch.api.research.provider.ProviderDataNotFoundException;
import com.aiquantresearch.api.research.provider.runtime.ProviderCall;
import com.aiquantresearch.api.research.provider.runtime.ProviderRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
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
@ConditionalOnProperty(name = "app.providers.fundamental", havingValue = "sec-xbrl")
public class SecCompanyFactsFundamentalProvider implements FundamentalDataProvider {

    static final String PROVIDER = "SEC_EDGAR_XBRL";
    static final String SCHEMA_VERSION = "sec_companyfacts_normalized_v1";
    static final String MAPPING_VERSION = "us_gaap_fundamentals_v1";
    static final String LICENSE_POLICY_VERSION = "sec_public_edgar_2025_04_08";

    private static final Pattern ACCESSION = Pattern.compile("^[0-9]{10}-[0-9]{2}-[0-9]{6}$");
    private static final Set<String> ANNUAL_FORMS = Set.of("10-K", "10-K/A");
    private static final Set<String> INSTANT_FORMS = Set.of("10-K", "10-K/A", "10-Q", "10-Q/A");
    private static final List<String> REVENUE = List.of(
            "RevenueFromContractWithCustomerExcludingAssessedTax",
            "Revenues",
            "SalesRevenueNet"
    );
    private static final List<String> NET_INCOME = List.of("NetIncomeLoss", "ProfitLoss");
    private static final List<String> TOTAL_DEBT = List.of(
            "LongTermDebtAndFinanceLeaseObligations",
            "LongTermDebtAndCapitalLeaseObligations",
            "LongTermDebt"
    );

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final SecEdgarProperties properties;
    private final SecRequestGovernor governor;
    private final Clock clock;
    private final ProviderRuntime runtime;

    public SecCompanyFactsFundamentalProvider(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            SecEdgarProperties properties,
            Clock clock
    ) {
        this(builder, objectMapper, properties, clock, ProviderRuntime.direct());
    }

    @Autowired
    public SecCompanyFactsFundamentalProvider(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            SecEdgarProperties properties,
            Clock clock,
            ProviderRuntime runtime
    ) {
        properties.requireConfiguredIdentity();
        validateEndpoint(properties.dataBaseUrl(), "data.sec.gov");
        validateEndpoint(properties.companyTickersUrl(), "www.sec.gov");
        webClient = builder.clone()
                .codecs(codecs -> codecs.defaultCodecs()
                        .maxInMemorySize(properties.maxJsonBytes()))
                .build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        governor = new SecRequestGovernor(properties.maxRequestsPerSecond());
        this.clock = clock;
        this.runtime = runtime;
    }

    @Override
    public FundamentalDataSnapshot fetch(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        return runtime.execute(
                new ProviderCall<>(
                        PROVIDER, "secEdgar", SCHEMA_VERSION,
                        normalizedSymbol, FundamentalDataSnapshot.class
                ),
                () -> fetchLive(normalizedSymbol)
        );
    }

    private FundamentalDataSnapshot fetchLive(String normalizedSymbol) {
        List<byte[]> rawParts = new ArrayList<>();
        byte[] tickerBytes = fetchBytes(properties.companyTickersUrl());
        rawParts.add(tickerBytes);
        CompanyIdentity company = company(parseJson(tickerBytes, "SEC_TICKERS_SCHEMA_INVALID"),
                normalizedSymbol);
        URI companyFactsUri = UriComponentsBuilder.fromUri(properties.dataBaseUrl())
                .pathSegment("api", "xbrl", "companyfacts", "CIK%010d.json".formatted(company.cik()))
                .build(true)
                .toUri();
        byte[] companyFactsBytes = fetchBytes(companyFactsUri);
        rawParts.add(companyFactsBytes);
        JsonNode root = parseJson(companyFactsBytes, "SEC_COMPANYFACTS_SCHEMA_INVALID");
        validateCompany(root, company);
        LocalDate retrievalDate = LocalDate.now(clock);
        List<String> warnings = new ArrayList<>();
        List<FundamentalMetric> metrics = normalize(root, retrievalDate, warnings);
        if (metrics.isEmpty()) {
            throw permanent("SEC_XBRL_NO_SUPPORTED_FACTS",
                    "SEC Companyfacts contains no supported, period-valid facts");
        }
        LocalDate effectiveDate = metrics.stream()
                .map(FundamentalMetric::periodEndDate)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        LocalDate coverageDate = metrics.stream()
                .filter(metric -> "FY".equals(metric.periodType()))
                .map(FundamentalMetric::periodEndDate)
                .max(Comparator.naturalOrder())
                .orElse(effectiveDate);
        if (metrics.stream().map(FundamentalMetric::periodEndDate).distinct().count() > 1) {
            warnings.add("MIXED_PERIODS_PRESENT");
        }
        Instant retrievedAt = clock.instant();
        return new FundamentalDataSnapshot(
                PROVIDER,
                SCHEMA_VERSION,
                null,
                normalizedSymbol,
                effectiveDate,
                retrievedAt,
                companyFactsUri.toString(),
                combinedHash(rawParts),
                metrics,
                List.of(),
                warnings,
                null,
                false,
                freshness(coverageDate, retrievalDate),
                LICENSE_POLICY_VERSION
        );
    }

    private List<FundamentalMetric> normalize(
            JsonNode root,
            LocalDate asOf,
            List<String> warnings
    ) {
        List<FundamentalMetric> metrics = new ArrayList<>();
        Optional<Fact> revenue = annual(root, REVENUE, "USD", asOf);
        Optional<Fact> operatingIncome = annual(root, List.of("OperatingIncomeLoss"), "USD", asOf);
        addDirect(metrics, warnings, "revenue", revenue, "USD", "FY");
        addDirect(metrics, warnings, "operatingIncome", operatingIncome, "USD", "FY");
        addDirect(metrics, warnings, "netIncome", annual(root, NET_INCOME, "USD", asOf),
                "USD", "FY");
        addDirect(metrics, warnings, "dilutedShares", annual(root,
                        List.of("WeightedAverageNumberOfDilutedSharesOutstanding"), "shares", asOf),
                "SHARES", "FY");

        deriveRatio(metrics, warnings, "grossMargin", annual(root, List.of("GrossProfit"),
                "USD", asOf), revenue);
        deriveDifference(metrics, warnings, "freeCashFlow",
                annual(root, List.of("NetCashProvidedByUsedInOperatingActivities"), "USD", asOf),
                annual(root, List.of("PaymentsToAcquirePropertyPlantAndEquipment"), "USD", asOf),
                "USD", false);
        deriveSum(metrics, warnings, "ebitda", operatingIncome,
                annual(root, List.of("DepreciationDepletionAndAmortization"), "USD", asOf),
                "USD");
        deriveDifference(metrics, warnings, "netDebt", instant(root, TOTAL_DEBT, "USD", asOf),
                instant(root, List.of("CashAndCashEquivalentsAtCarryingValue"), "USD", asOf),
                "USD", true);
        return List.copyOf(metrics);
    }

    private static void addDirect(
            List<FundamentalMetric> metrics,
            List<String> warnings,
            String name,
            Optional<Fact> fact,
            String unit,
            String periodType
    ) {
        if (fact.isEmpty()) {
            warnings.add("METRIC_NOT_AVAILABLE:" + name);
            return;
        }
        Fact value = fact.get();
        metrics.add(new FundamentalMetric(
                name, value.value(), unit, periodType, value.end(), "us-gaap",
                value.concept(), value.accession(), value.filed(), false,
                List.of(value.concept())
        ));
    }

    private static void deriveRatio(
            List<FundamentalMetric> metrics,
            List<String> warnings,
            String name,
            Optional<Fact> numerator,
            Optional<Fact> denominator
    ) {
        if (!samePeriod(numerator, denominator)
                || denominator.orElseThrow().value().signum() == 0) {
            warnings.add("METRIC_NOT_AVAILABLE:" + name);
            return;
        }
        Fact left = numerator.orElseThrow();
        Fact right = denominator.orElseThrow();
        addDerived(metrics, name, left.value().divide(right.value(), 12, RoundingMode.HALF_EVEN),
                "RATIO", "FY", left, right);
    }

    private static void deriveDifference(
            List<FundamentalMetric> metrics,
            List<String> warnings,
            String name,
            Optional<Fact> left,
            Optional<Fact> right,
            String unit,
            boolean pointInTime
    ) {
        if (!samePeriod(left, right)) {
            warnings.add("METRIC_NOT_AVAILABLE:" + name);
            return;
        }
        addDerived(metrics, name, left.orElseThrow().value().subtract(right.orElseThrow().value()),
                unit, pointInTime ? "POINT_IN_TIME" : "FY",
                left.orElseThrow(), right.orElseThrow());
    }

    private static void deriveSum(
            List<FundamentalMetric> metrics,
            List<String> warnings,
            String name,
            Optional<Fact> left,
            Optional<Fact> right,
            String unit
    ) {
        if (!samePeriod(left, right)) {
            warnings.add("METRIC_NOT_AVAILABLE:" + name);
            return;
        }
        addDerived(metrics, name, left.orElseThrow().value().add(right.orElseThrow().value()),
                unit, "FY", left.orElseThrow(), right.orElseThrow());
    }

    private static void addDerived(
            List<FundamentalMetric> metrics,
            String name,
            BigDecimal value,
            String unit,
            String periodType,
            Fact left,
            Fact right
    ) {
        metrics.add(new FundamentalMetric(
                name, value, unit, periodType, left.end(), "us-gaap",
                MAPPING_VERSION + ":" + name,
                left.accession().equals(right.accession()) ? left.accession() : null,
                left.filed().isAfter(right.filed()) ? left.filed() : right.filed(),
                true,
                List.of(left.concept(), right.concept())
        ));
    }

    private Optional<Fact> annual(
            JsonNode root,
            List<String> concepts,
            String unit,
            LocalDate asOf
    ) {
        for (String concept : concepts) {
            Optional<Fact> selected = facts(root, concept, unit).stream()
                    .filter(value -> ANNUAL_FORMS.contains(value.form()))
                    .filter(value -> "FY".equals(value.fiscalPeriod()))
                    .filter(value -> value.start() != null)
                    .filter(value -> {
                        long days = ChronoUnit.DAYS.between(value.start(), value.end());
                        return days >= 300 && days <= 400;
                    })
                    .filter(value -> !value.filed().isAfter(asOf))
                    .filter(value -> !value.end().isAfter(asOf))
                    .filter(value -> !value.filed().isBefore(value.end()))
                    .max(Comparator.comparing(Fact::end).thenComparing(Fact::filed));
            if (selected.isPresent()) {
                return selected;
            }
        }
        return Optional.empty();
    }

    private Optional<Fact> instant(
            JsonNode root,
            List<String> concepts,
            String unit,
            LocalDate asOf
    ) {
        for (String concept : concepts) {
            Optional<Fact> selected = facts(root, concept, unit).stream()
                    .filter(value -> INSTANT_FORMS.contains(value.form()))
                    .filter(value -> value.start() == null)
                    .filter(value -> !value.filed().isAfter(asOf))
                    .filter(value -> !value.end().isAfter(asOf))
                    .filter(value -> !value.filed().isBefore(value.end()))
                    .max(Comparator.comparing(Fact::end).thenComparing(Fact::filed));
            if (selected.isPresent()) {
                return selected;
            }
        }
        return Optional.empty();
    }

    private List<Fact> facts(JsonNode root, String concept, String unit) {
        JsonNode values = root.path("facts").path("us-gaap").path(concept)
                .path("units").path(unit);
        if (!values.isArray()) {
            return List.of();
        }
        List<Fact> result = new ArrayList<>();
        for (JsonNode value : values) {
            try {
                String accession = value.path("accn").asText();
                if (!ACCESSION.matcher(accession).matches() || !value.path("val").isNumber()) {
                    continue;
                }
                String startText = value.path("start").asText();
                result.add(new Fact(
                        concept,
                        unit,
                        startText.isBlank() ? null : LocalDate.parse(startText),
                        LocalDate.parse(requiredText(value, "end")),
                        value.path("val").decimalValue(),
                        accession,
                        LocalDate.parse(requiredText(value, "filed")),
                        requiredText(value, "form"),
                        value.path("fp").asText()
                ));
            } catch (java.time.DateTimeException | IllegalArgumentException ignored) {
                // A malformed individual context is excluded; the metric remains unavailable
                // unless another complete, unit-compatible context exists.
            }
        }
        return result;
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
                    throw permanent("SEC_RESPONSE_EMPTY", "SEC returned an empty response");
                }
                MediaType contentType = response.getHeaders().getContentType();
                if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
                    throw permanent("SEC_CONTENT_TYPE_INVALID",
                            "SEC returned an unexpected content type");
                }
                if (response.getBody().length > properties.maxJsonBytes()) {
                    throw permanent("SEC_RESPONSE_TOO_LARGE",
                            "SEC response exceeds the configured byte boundary");
                }
                return response.getBody();
            } catch (DataBufferLimitException exception) {
                throw permanent("SEC_RESPONSE_TOO_LARGE",
                        "SEC response exceeds the configured byte boundary");
            } catch (WebClientResponseException exception) {
                int status = exception.getStatusCode().value();
                last = new ProviderAccessException(
                        "SEC_HTTP_" + status,
                        "SEC request failed with HTTP " + status,
                        status == 429 || status == 502 || status == 503
                );
            } catch (WebClientRequestException exception) {
                last = new ProviderAccessException(
                        "SEC_UNAVAILABLE", "SEC is temporarily unavailable", true);
            } catch (IllegalStateException exception) {
                last = new ProviderAccessException(
                        "SEC_TIMEOUT", "SEC request exceeded the configured timeout", true);
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

    private JsonNode parseJson(byte[] bytes, String code) {
        try {
            JsonNode root = objectMapper.readTree(bytes);
            if (root == null || !root.isObject()) {
                throw permanent(code, "SEC returned an invalid JSON root");
            }
            return root;
        } catch (IOException exception) {
            throw permanent(code, "SEC returned malformed JSON");
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

    private static void validateCompany(JsonNode root, CompanyIdentity expected) {
        int cik = root.path("cik").asInt(0);
        if (cik != expected.cik() || !root.path("facts").isObject()) {
            throw permanent("SEC_COMPANYFACTS_SCHEMA_INVALID",
                    "SEC Companyfacts does not match the requested company");
        }
    }

    private static boolean samePeriod(Optional<Fact> left, Optional<Fact> right) {
        return left.isPresent() && right.isPresent()
                && left.get().end().equals(right.get().end())
                && java.util.Objects.equals(left.get().start(), right.get().start());
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("missing field");
        }
        return value;
    }

    private static String normalizeSymbol(String symbol) {
        String value = symbol == null ? "" : symbol.strip().toUpperCase(Locale.ROOT);
        if (!value.matches("^[A-Z0-9][A-Z0-9.-]{0,31}$")) {
            throw permanent("SEC_SYMBOL_INVALID", "The SEC symbol is invalid");
        }
        return value;
    }

    private static String freshness(LocalDate latestPeriod, LocalDate asOf) {
        long age = ChronoUnit.DAYS.between(latestPeriod, asOf);
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
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(part.length).array());
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

    private record Fact(
            String concept,
            String unit,
            LocalDate start,
            LocalDate end,
            BigDecimal value,
            String accession,
            LocalDate filed,
            String form,
            String fiscalPeriod
    ) {
    }
}
