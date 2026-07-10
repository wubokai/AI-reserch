package com.aiquantresearch.api.research.artifactapi;

import com.aiquantresearch.api.research.application.ResearchNotFoundException;
import com.aiquantresearch.api.shared.config.ApplicationProperties;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArtifactQueryService {

    private static final String OWNED_RESEARCH_SQL = """
            select r.id, r.data_mode
              from research_jobs r
             where r.id = ?
               and r.user_id = ?
               and r.deleted_at is null
               and r.data_mode <> 'MIXED_TEST'
            """;
    private static final String PUBLISHED_REPORT_FILTER =
            " rv.validation_status in ('PASSED', 'PASSED_WITH_WARNINGS')";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ApplicationProperties applicationProperties;
    private final Clock clock;

    public ArtifactQueryService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ApplicationProperties applicationProperties,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.applicationProperties = applicationProperties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ArtifactApiResponses.SecuritySearchResponse searchSecurities(
            String query,
            String securityType,
            String exchange,
            int limit
    ) {
        DataMode mode = applicationProperties.dataMode();
        if (mode == DataMode.MIXED_TEST) {
            return new ArtifactApiResponses.SecuritySearchResponse(List.of(), mode);
        }
        String normalized = query.strip().toUpperCase(Locale.ROOT);
        String prefix = escapeLike(normalized) + "%";
        var sql = new StringBuilder("""
                select s.id as security_id, s.symbol, s.company_name, s.exchange,
                       s.security_type, s.currency, s.cik, s.active, s.is_demo_data
                  from securities s
                 where s.active
                   and s.is_demo_data = ?
                   and (upper(s.symbol) like ? escape '\\'
                        or upper(s.company_name) like ? escape '\\')
                """);
        var args = new ArrayList<Object>();
        args.add(mode == DataMode.MOCK);
        args.add(prefix);
        args.add(prefix);
        if (securityType != null) {
            sql.append(" and s.security_type = ?");
            args.add(securityType);
        }
        if (exchange != null) {
            sql.append(" and upper(s.exchange) = upper(?)");
            args.add(exchange.strip());
        }
        sql.append("""
                 order by case when upper(s.symbol) = ? then 0 else 1 end,
                          length(s.symbol), s.symbol, s.exchange
                 limit ?
                """);
        args.add(normalized);
        args.add(limit);
        List<ArtifactApiResponses.SecurityItem> items = jdbc
                .queryForList(sql.toString(), args.toArray())
                .stream()
                .map(this::securityItem)
                .toList();
        return new ArtifactApiResponses.SecuritySearchResponse(items, mode);
    }

    public ArtifactApiResponses.ProviderStatusResponse providerStatus() {
        Instant now = clock.instant();
        DataMode mode = applicationProperties.dataMode();
        if (mode == DataMode.MOCK) {
            var unlimited = ArtifactApiResponses.RateLimitStatus.unlimited();
            return new ArtifactApiResponses.ProviderStatusResponse(
                    "UP",
                    mode,
                    List.of(
                            new ArtifactApiResponses.ProviderStatus(
                                    "Deterministic Mock Data",
                                    List.of("MARKET_DATA", "FUNDAMENTALS", "FILINGS", "MACRO"),
                                    "MOCK",
                                    "UP",
                                    true,
                                    now,
                                    now,
                                    0L,
                                    unlimited,
                                    "Fixed versioned fixtures"
                            ),
                            new ArtifactApiResponses.ProviderStatus(
                                    "Deterministic Mock LLM",
                                    List.of("LLM"),
                                    "MOCK",
                                    "UP",
                                    true,
                                    now,
                                    now,
                                    0L,
                                    unlimited,
                                    "Evidence-constrained deterministic generator"
                            ),
                            new ArtifactApiResponses.ProviderStatus(
                                    "Analytics",
                                    List.of("ANALYTICS"),
                                    "REAL",
                                    "UNKNOWN",
                                    true,
                                    now,
                                    null,
                                    null,
                                    unlimited,
                                    "Runtime health is reported by the dedicated health endpoint"
                            )
                    ),
                    now
            );
        }
        return new ArtifactApiResponses.ProviderStatusResponse(
                mode == DataMode.MIXED_TEST ? "UNKNOWN" : "DEGRADED",
                mode,
                List.of(),
                now
        );
    }

    @Transactional(readOnly = true)
    public ArtifactApiResponses.EvidencePage evidence(
            UUID ownerId,
            UUID researchId,
            int page,
            int size,
            String claimType,
            String sourceType,
            String freshnessStatus,
            Boolean isDemoData
    ) {
        OwnedResearch research = requireOwned(ownerId, researchId);
        var where = new StringBuilder("""
                from evidence_items e
                join research_jobs r on r.id = e.research_job_id
                left join source_snapshots ss on ss.id = e.source_snapshot_id
                left join quant_results qr on qr.id = e.quant_result_id
               where e.research_job_id = ?
                 and r.user_id = ?
                 and r.deleted_at is null
                 and r.data_mode <> 'MIXED_TEST'
                """);
        var args = new ArrayList<Object>();
        args.add(researchId);
        args.add(ownerId);
        if (claimType != null) {
            where.append("""
                     and exists (
                         select 1
                           from claim_evidence_links cel
                           join claims c on c.id = cel.claim_id
                           join report_versions linked_report on linked_report.id = c.report_version_id
                          where cel.evidence_id = e.id
                            and c.claim_type = ?
                            and linked_report.validation_status in ('PASSED', 'PASSED_WITH_WARNINGS')
                     )
                    """);
            args.add(claimType);
        }
        if (sourceType != null) {
            where.append(" and coalesce(ss.source_type, 'INTERNAL_CALCULATION') = ?");
            args.add(sourceType);
        }
        if (freshnessStatus != null) {
            where.append(" and coalesce(ss.freshness_status, 'FRESH') = ?");
            args.add(freshnessStatus);
        }
        if (isDemoData != null) {
            where.append(" and e.is_demo_data = ?");
            args.add(isDemoData);
        }

        long total = count("select count(*) " + where, args);
        String sql = """
                select e.public_id as evidence_id, e.evidence_type, e.title, e.summary,
                       e.value_json::text as value_json, e.unit,
                       coalesce(ss.provider, 'Internal Analytics') as source_name,
                       ss.source_url,
                       coalesce(ss.source_type, 'INTERNAL_CALCULATION') as source_type,
                       ss.published_at,
                       coalesce(ss.retrieved_at, e.created_at) as retrieved_at,
                       coalesce(ss.effective_date, qr.input_data_end) as effective_date,
                       coalesce(ss.is_primary_source, true) as is_primary_source,
                       coalesce(ss.freshness_status, 'FRESH') as freshness_status,
                       e.quality_score, coalesce(ss.raw_data_hash, qr.input_hash) as raw_data_hash,
                       e.is_demo_data, ss.id as source_snapshot_id,
                       ss.schema_version as source_schema_version,
                       ss.normalized_data_hash,
                       coalesce((
                           select jsonb_agg(distinct c.public_id order by c.public_id)
                             from claim_evidence_links cel
                             join claims c on c.id = cel.claim_id
                             join report_versions linked_report
                               on linked_report.id = c.report_version_id
                            where cel.evidence_id = e.id
                              and linked_report.validation_status
                                  in ('PASSED', 'PASSED_WITH_WARNINGS')
                       ), '[]'::jsonb)::text as related_claim_ids_json
                """ + where + " order by e.created_at desc, e.public_id limit ? offset ?";
        var pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(Math.multiplyExact(page, size));
        List<ArtifactApiResponses.EvidenceItem> items = jdbc
                .queryForList(sql, pageArgs.toArray())
                .stream()
                .map(this::evidenceItem)
                .toList();
        return new ArtifactApiResponses.EvidencePage(
                items,
                ArtifactApiResponses.PageMetadata.of(page, size, total),
                research.dataMode()
        );
    }

    @Transactional(readOnly = true)
    public ArtifactApiResponses.EvidenceSearchResponse searchEvidence(
            UUID ownerId,
            UUID researchId,
            String query,
            int limit
    ) {
        OwnedResearch research = requireOwned(ownerId, researchId);
        List<ArtifactApiResponses.EvidenceSearchResult> items = jdbc.queryForList("""
                select e.public_id as evidence_id, f.id as filing_id,
                       fc.id as chunk_id, f.external_document_id, f.form_type,
                       f.filing_date, fc.section_name, fc.chunk_index,
                       ts_headline(
                           'english', fc.content, websearch_to_tsquery('english', ?),
                           'StartSel=<mark>, StopSel=</mark>, MaxWords=45, MinWords=15'
                       ) as excerpt,
                       'filing:' || f.external_document_id || '#'
                           || fc.section_name || ':chunk=' || fc.chunk_index
                           || ':chars=' || fc.character_start || '-' || fc.character_end
                           as citation_locator,
                       ts_rank_cd(fc.search_vector, websearch_to_tsquery('english', ?)) as rank,
                       f.is_demo_data
                  from filing_chunks fc
                  join filings f on f.id = fc.filing_id
                  join research_source_links rsl on rsl.source_snapshot_id = f.source_snapshot_id
                  join research_jobs r on r.id = rsl.research_job_id
                  join evidence_items e
                    on e.research_job_id = r.id
                   and e.source_snapshot_id = f.source_snapshot_id
                 where r.id = ? and r.user_id = ? and r.deleted_at is null
                   and r.data_mode <> 'MIXED_TEST'
                   and fc.search_vector @@ websearch_to_tsquery('english', ?)
                 order by rank desc, f.filing_date desc, fc.section_name, fc.chunk_index
                 limit ?
                """, query, query, researchId, ownerId, query, limit).stream()
                .map(this::evidenceSearchResult)
                .toList();
        return new ArtifactApiResponses.EvidenceSearchResponse(
                query,
                items,
                research.dataMode()
        );
    }

    @Transactional(readOnly = true)
    public ArtifactApiResponses.ReportVersionPage reports(
            UUID ownerId,
            UUID researchId,
            int page,
            int size
    ) {
        requireOwned(ownerId, researchId);
        String from = """
                 from report_versions rv
                 join research_jobs r on r.id = rv.research_job_id
                where rv.research_job_id = ?
                  and r.user_id = ?
                  and r.deleted_at is null
                  and r.data_mode <> 'MIXED_TEST'
                  and rv.data_mode <> 'MIXED_TEST'
                  and """ + PUBLISHED_REPORT_FILTER;
        var args = List.<Object>of(researchId, ownerId);
        long total = count("select count(*) " + from, args);
        String sql = """
                select rv.research_job_id, rv.version,
                       rv.report_json ->> 'title' as title,
                       rv.report_json ->> 'symbol' as symbol,
                       rv.data_as_of_date, rv.validation_status,
                       rv.data_mode, rv.content_hash, rv.created_at
                """ + from + " order by rv.version desc limit ? offset ?";
        List<ArtifactApiResponses.ReportVersionSummary> items = jdbc.queryForList(
                        sql,
                        researchId,
                        ownerId,
                        size,
                        Math.multiplyExact(page, size)
                ).stream()
                .map(this::reportSummary)
                .toList();
        return new ArtifactApiResponses.ReportVersionPage(
                items,
                ArtifactApiResponses.PageMetadata.of(page, size, total)
        );
    }

    @Transactional(readOnly = true)
    public ArtifactApiResponses.ReportVersionResponse report(
            UUID ownerId,
            UUID researchId,
            int version
    ) {
        requireOwned(ownerId, researchId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select rv.research_job_id, rv.version, rv.validation_status,
                       rv.content_hash, rv.created_at, rv.generated_at,
                       rv.report_json::text as report_json
                  from report_versions rv
                  join research_jobs r on r.id = rv.research_job_id
                 where rv.research_job_id = ?
                   and rv.version = ?
                   and r.user_id = ?
                   and r.deleted_at is null
                   and r.data_mode <> 'MIXED_TEST'
                   and rv.data_mode <> 'MIXED_TEST'
                   and rv.validation_status in ('PASSED', 'PASSED_WITH_WARNINGS')
                """, researchId, version, ownerId);
        if (rows.isEmpty()) {
            throw new ResearchNotFoundException(researchId);
        }
        Map<String, Object> row = rows.getFirst();
        return new ArtifactApiResponses.ReportVersionResponse(
                uuid(row, "research_job_id"),
                integer(row, "version"),
                text(row, "validation_status"),
                text(row, "content_hash"),
                instant(row, "created_at"),
                instant(row, "generated_at"),
                json(text(row, "report_json"))
        );
    }

    private OwnedResearch requireOwned(UUID ownerId, UUID researchId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                OWNED_RESEARCH_SQL,
                researchId,
                ownerId
        );
        if (rows.isEmpty()) {
            throw new ResearchNotFoundException(researchId);
        }
        return new OwnedResearch(
                uuid(rows.getFirst(), "id"),
                DataMode.valueOf(text(rows.getFirst(), "data_mode"))
        );
    }

    private long count(String sql, List<Object> args) {
        Long value = jdbc.queryForObject(sql, Long.class, args.toArray());
        return value == null ? 0 : value;
    }

    private ArtifactApiResponses.SecurityItem securityItem(Map<String, Object> row) {
        boolean demo = bool(row, "is_demo_data");
        return new ArtifactApiResponses.SecurityItem(
                uuid(row, "security_id"),
                text(row, "symbol"),
                text(row, "company_name"),
                text(row, "exchange"),
                text(row, "security_type"),
                text(row, "currency"),
                null,
                null,
                nullableText(row, "cik"),
                bool(row, "active"),
                demo ? DataMode.MOCK : DataMode.REAL
        );
    }

    private ArtifactApiResponses.EvidenceItem evidenceItem(Map<String, Object> row) {
        return new ArtifactApiResponses.EvidenceItem(
                text(row, "evidence_id"),
                text(row, "evidence_type"),
                text(row, "title"),
                text(row, "summary"),
                json(text(row, "value_json")),
                nullableText(row, "unit"),
                text(row, "source_name"),
                nullableText(row, "source_url"),
                text(row, "source_type"),
                nullableInstant(row, "published_at"),
                instant(row, "retrieved_at"),
                nullableDate(row, "effective_date"),
                bool(row, "is_primary_source"),
                text(row, "freshness_status"),
                decimal(row, "quality_score").doubleValue(),
                text(row, "raw_data_hash"),
                bool(row, "is_demo_data"),
                stringList(text(row, "related_claim_ids_json")),
                nullableUuid(row, "source_snapshot_id"),
                nullableText(row, "source_schema_version"),
                nullableText(row, "normalized_data_hash")
        );
    }

    private ArtifactApiResponses.EvidenceSearchResult evidenceSearchResult(
            Map<String, Object> row
    ) {
        return new ArtifactApiResponses.EvidenceSearchResult(
                text(row, "evidence_id"),
                uuid(row, "filing_id"),
                uuid(row, "chunk_id"),
                text(row, "external_document_id"),
                text(row, "form_type"),
                date(row, "filing_date"),
                text(row, "section_name"),
                integer(row, "chunk_index"),
                text(row, "excerpt"),
                text(row, "citation_locator"),
                decimal(row, "rank").doubleValue(),
                bool(row, "is_demo_data")
        );
    }

    private ArtifactApiResponses.ReportVersionSummary reportSummary(Map<String, Object> row) {
        return new ArtifactApiResponses.ReportVersionSummary(
                uuid(row, "research_job_id"),
                integer(row, "version"),
                text(row, "title"),
                text(row, "symbol"),
                date(row, "data_as_of_date"),
                text(row, "validation_status"),
                DataMode.valueOf(text(row, "data_mode")),
                text(row, "content_hash"),
                instant(row, "created_at")
        );
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted Phase 3 JSON is invalid", exception);
        }
    }

    private List<String> stringList(String value) {
        JsonNode node = json(value);
        if (!node.isArray()) {
            throw new IllegalStateException("Persisted ID collection is invalid");
        }
        var result = new ArrayList<String>(node.size());
        node.forEach(item -> result.add(item.asText()));
        return List.copyOf(result);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            throw new IllegalStateException("Required database column is null: " + key);
        }
        return value.toString();
    }

    private static String nullableText(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private static UUID uuid(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof UUID uuid ? uuid : UUID.fromString(text(row, key));
    }

    private static UUID nullableUuid(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private static int integer(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.intValue() : Integer.parseInt(text(row, key));
    }

    private static boolean bool(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Boolean flag ? flag : Boolean.parseBoolean(text(row, key));
    }

    private static BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(text(row, key));
    }

    private static Instant instant(Map<String, Object> row, String key) {
        Instant value = nullableInstant(row, key);
        if (value == null) {
            throw new IllegalStateException("Required database timestamp is null: " + key);
        }
        return value;
    }

    private static Instant nullableInstant(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return switch (value) {
            case null -> null;
            case Instant instant -> instant;
            case Timestamp timestamp -> timestamp.toInstant();
            case OffsetDateTime dateTime -> dateTime.toInstant();
            default -> throw new IllegalStateException("Unsupported timestamp value for " + key);
        };
    }

    private static LocalDate date(Map<String, Object> row, String key) {
        LocalDate value = nullableDate(row, key);
        if (value == null) {
            throw new IllegalStateException("Required database date is null: " + key);
        }
        return value;
    }

    private static LocalDate nullableDate(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return switch (value) {
            case null -> null;
            case LocalDate date -> date;
            case java.sql.Date date -> date.toLocalDate();
            default -> throw new IllegalStateException("Unsupported date value for " + key);
        };
    }

    private record OwnedResearch(UUID id, DataMode dataMode) {
    }
}
