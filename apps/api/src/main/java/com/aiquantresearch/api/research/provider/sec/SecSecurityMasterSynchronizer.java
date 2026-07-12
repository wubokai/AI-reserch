package com.aiquantresearch.api.research.provider.sec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@ConditionalOnExpression("'${app.data-mode:MOCK}' == 'REAL' and "
        + "'${app.providers.fundamental:mock}' == 'sec-xbrl'")
public class SecSecurityMasterSynchronizer {

    private static final Logger LOG = LoggerFactory.getLogger(SecSecurityMasterSynchronizer.class);
    private static final Pattern SYMBOL = Pattern.compile("^[A-Z][A-Z0-9.-]{0,9}$");
    private static final String UPSERT_SQL = """
            insert into securities (
                symbol, company_name, exchange, security_type, currency, cik,
                active, is_demo_data
            ) values (?, ?, ?, 'COMMON_STOCK', 'USD', ?, true, false)
            on conflict ((upper(symbol)), (upper(exchange)), is_demo_data)
            do update set company_name = excluded.company_name,
                          cik = excluded.cik,
                          active = true,
                          updated_at = statement_timestamp(),
                          row_version = securities.row_version + 1
            """;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final SecEdgarProperties properties;
    private final SecRequestGovernor governor;

    public SecSecurityMasterSynchronizer(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            JdbcTemplate jdbc,
            SecEdgarProperties properties,
            SecRequestGovernor governor
    ) {
        properties.requireConfiguredIdentity();
        validateEndpoint(properties.companyTickersExchangeUrl());
        this.webClient = builder.clone()
                .codecs(codecs -> codecs.defaultCodecs()
                        .maxInMemorySize(properties.maxJsonBytes()))
                .build();
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.properties = properties;
        this.governor = governor;
    }

    @Scheduled(
            initialDelayString = "${app.providers.sec.security-master-initial-delay:3s}",
            fixedDelayString = "${app.providers.sec.security-master-refresh-delay:24h}"
    )
    public void synchronizeSafely() {
        try {
            int count = synchronizeNow();
            LOG.info("SEC security master synchronized securityCount={}", count);
        } catch (RuntimeException exception) {
            LOG.warn("SEC security master synchronization failed safely errorType={}",
                    exception.getClass().getSimpleName());
        }
    }

    int synchronizeNow() {
        byte[] raw = fetch(properties.companyTickersExchangeUrl());
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (IOException exception) {
            throw new IllegalStateException("SEC security master returned malformed JSON", exception);
        }
        List<SecurityIdentity> identities = parseCatalog(root);
        if (identities.isEmpty()) {
            throw new IllegalStateException("SEC security master contained no supported securities");
        }
        jdbc.batchUpdate(
                UPSERT_SQL,
                identities,
                500,
                SecSecurityMasterSynchronizer::bind
        );
        return identities.size();
    }

    static List<SecurityIdentity> parseCatalog(JsonNode root) {
        JsonNode fields = root.path("fields");
        JsonNode data = root.path("data");
        if (!fields.isArray() || !data.isArray()) {
            throw new IllegalStateException("SEC security master schema is invalid");
        }
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int index = 0; index < fields.size(); index++) {
            indexes.put(fields.get(index).asText(), index);
        }
        for (String required : List.of("cik", "name", "ticker", "exchange")) {
            if (!indexes.containsKey(required)) {
                throw new IllegalStateException("SEC security master schema is incomplete");
            }
        }
        Map<String, SecurityIdentity> unique = new LinkedHashMap<>();
        for (JsonNode row : data) {
            if (!row.isArray()) {
                continue;
            }
            String symbol = value(row, indexes.get("ticker")).toUpperCase(Locale.ROOT);
            String name = value(row, indexes.get("name"));
            String exchange = normalizeExchange(value(row, indexes.get("exchange")));
            String cik = value(row, indexes.get("cik"));
            if (!SYMBOL.matcher(symbol).matches() || name.isBlank()
                    || exchange.isBlank() || !cik.matches("^[0-9]{1,10}$")) {
                continue;
            }
            String safeName = name.length() > 255 ? name.substring(0, 255) : name;
            var identity = new SecurityIdentity(symbol, safeName, exchange, cik);
            unique.put(symbol + "|" + exchange, identity);
        }
        return List.copyOf(unique.values());
    }

    private byte[] fetch(URI uri) {
        governor.acquire();
        ResponseEntity<byte[]> response = webClient.get()
                .uri(uri)
                .header(HttpHeaders.USER_AGENT, properties.userAgent())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .toEntity(byte[].class)
                .block(properties.timeout());
        if (response == null || response.getBody() == null || response.getBody().length == 0) {
            throw new IllegalStateException("SEC security master returned an empty response");
        }
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            throw new IllegalStateException("SEC security master returned an invalid content type");
        }
        if (response.getBody().length > properties.maxJsonBytes()) {
            throw new IllegalStateException("SEC security master exceeded the response limit");
        }
        return response.getBody();
    }

    private static void bind(PreparedStatement statement, SecurityIdentity identity)
            throws SQLException {
        statement.setString(1, identity.symbol());
        statement.setString(2, identity.companyName());
        statement.setString(3, identity.exchange());
        statement.setString(4, identity.cik());
    }

    private static String value(JsonNode row, int index) {
        if (index < 0 || index >= row.size() || row.get(index).isNull()) {
            return "";
        }
        return row.get(index).asText().strip();
    }

    private static String normalizeExchange(String value) {
        return value.toUpperCase(Locale.ROOT)
                .replace(" ", "")
                .replace("NASDAQGLOBALSELECTMARKET", "NASDAQ")
                .replace("NASDAQGLOBALMARKET", "NASDAQ")
                .replace("NASDAQCAPITALMARKET", "NASDAQ");
    }

    private static void validateEndpoint(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException("SEC security master URL is invalid");
        }
        String host = uri.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        boolean official = "www.sec.gov".equalsIgnoreCase(host)
                && "https".equalsIgnoreCase(uri.getScheme());
        if (!loopback && !official) {
            throw new IllegalStateException("SEC security master URL is outside the approved host");
        }
    }

    record SecurityIdentity(String symbol, String companyName, String exchange, String cik) {
    }
}
