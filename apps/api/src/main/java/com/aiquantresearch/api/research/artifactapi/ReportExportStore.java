package com.aiquantresearch.api.research.artifactapi;

import com.aiquantresearch.api.research.orchestration.StoredEvidence;
import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ReportExportStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    ReportExportStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    Optional<ExportReportSource> findReport(
            UUID ownerId,
            UUID researchId,
            Integer reportVersion
    ) {
        String versionPredicate = reportVersion == null
                ? " rv.id = r.latest_report_version_id"
                : " rv.version = ?";
        String sql = """
                select rv.id as report_version_id, rv.research_job_id,
                       rv.version, rv.report_json::text as report_json,
                       rv.data_mode, rv.content_hash,
                       rv.report_json ->> 'symbol' as symbol
                  from report_versions rv
                  join research_jobs r on r.id = rv.research_job_id
                 where rv.research_job_id = ?
                   and r.user_id = ?
                   and r.deleted_at is null
                   and r.data_mode <> 'MIXED_TEST'
                   and rv.data_mode <> 'MIXED_TEST'
                   and rv.validation_status in ('PASSED', 'PASSED_WITH_WARNINGS')
                   and """ + versionPredicate;
        List<Map<String, Object>> rows = reportVersion == null
                ? jdbc.queryForList(sql, researchId, ownerId)
                : jdbc.queryForList(sql, researchId, ownerId, reportVersion);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        return Optional.of(new ExportReportSource(
                uuid(row, "report_version_id"),
                uuid(row, "research_job_id"),
                integer(row, "version"),
                json(text(row, "report_json")),
                DataMode.valueOf(text(row, "data_mode")),
                text(row, "content_hash"),
                text(row, "symbol")
        ));
    }

    Optional<CachedReportExport> cached(
            UUID reportVersionId,
            ReportExportFormat format,
            String templateVersion
    ) {
        return jdbc.queryForList("""
                select content_bytes, content_hash, size_bytes
                  from report_exports
                 where report_version_id = ?
                   and format = ?
                   and template_version = ?
                   and status = 'SUCCEEDED'
                 order by created_at desc, id desc
                 limit 1
                """, reportVersionId, format.name(), templateVersion)
                .stream()
                .findFirst()
                .map(this::cachedExport);
    }

    void lockReportVersion(UUID reportVersionId) {
        UUID locked = jdbc.queryForObject(
                "select id from report_versions where id = ? for update",
                UUID.class,
                reportVersionId
        );
        if (locked == null) {
            throw new IllegalStateException("The report version disappeared before export");
        }
    }

    List<StoredEvidence> evidence(UUID reportVersionId, UUID researchId) {
        return jdbc.queryForList("""
                select distinct e.id, e.public_id, e.evidence_type, e.title, e.summary,
                       e.value_json::text as value_json, e.unit,
                       e.source_snapshot_id, e.quant_result_id
                  from claims c
                  join claim_evidence_links cel on cel.claim_id = c.id
                  join evidence_items e on e.id = cel.evidence_id
                 where c.report_version_id = ?
                   and c.research_job_id = ?
                   and e.research_job_id = ?
                 order by e.public_id
                """, reportVersionId, researchId, researchId)
                .stream()
                .map(row -> new StoredEvidence(
                        uuid(row, "id"),
                        text(row, "public_id"),
                        text(row, "evidence_type"),
                        text(row, "title"),
                        text(row, "summary"),
                        json(text(row, "value_json")),
                        nullableText(row, "unit"),
                        nullableUuid(row, "source_snapshot_id"),
                        nullableUuid(row, "quant_result_id")
                ))
                .toList();
    }

    CachedReportExport cache(
            ExportReportSource source,
            ReportExportFormat format,
            String templateVersion,
            byte[] content,
            String contentHash
    ) {
        jdbc.update("""
                insert into report_exports (
                    id, report_version_id, research_job_id, format,
                    template_version, status, content_bytes,
                    content_hash, size_bytes
                ) values (?, ?, ?, ?, ?, 'SUCCEEDED', ?, ?, ?)
                on conflict (
                    report_version_id, format, template_version, content_hash
                ) where status = 'SUCCEEDED' do nothing
                """,
                UUID.randomUUID(),
                source.reportVersionId(),
                source.researchId(),
                format.name(),
                templateVersion,
                content,
                contentHash,
                content.length
        );
        return jdbc.queryForList("""
                select content_bytes, content_hash, size_bytes
                  from report_exports
                 where report_version_id = ?
                   and format = ?
                   and template_version = ?
                   and content_hash = ?
                   and status = 'SUCCEEDED'
                 order by created_at desc, id desc
                 limit 1
                """, source.reportVersionId(), format.name(), templateVersion, contentHash)
                .stream()
                .findFirst()
                .map(this::cachedExport)
                .orElseThrow(() -> new IllegalStateException(
                        "The deterministic report export cache could not be read"
                ));
    }

    private CachedReportExport cachedExport(Map<String, Object> row) {
        Object bytes = row.get("content_bytes");
        if (!(bytes instanceof byte[] content)) {
            throw new IllegalStateException("A successful report export has no content bytes");
        }
        long declaredSize = number(row, "size_bytes").longValue();
        if (declaredSize != content.length) {
            throw new IllegalStateException("The cached report export size is invalid");
        }
        return new CachedReportExport(content.clone(), text(row, "content_hash"));
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted Phase 3 JSON is invalid", exception);
        }
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
        if (value == null) {
            return null;
        }
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private static int integer(Map<String, Object> row, String key) {
        return number(row, key).intValue();
    }

    private static Number number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number;
        }
        throw new IllegalStateException("Required database number is invalid: " + key);
    }
}

record ExportReportSource(
        UUID reportVersionId,
        UUID researchId,
        int version,
        JsonNode report,
        DataMode dataMode,
        String reportContentHash,
        String symbol
) {
}

record CachedReportExport(byte[] content, String contentHash) {
    CachedReportExport {
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
