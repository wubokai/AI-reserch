package com.aiquantresearch.api.research.filing;

import com.aiquantresearch.api.research.application.CanonicalHashService;
import com.aiquantresearch.api.research.orchestration.StoredSource;
import com.aiquantresearch.api.research.worker.StepExecutionException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FilingRegistry {

    private final JdbcTemplate jdbc;
    private final FilingTextProcessor processor;
    private final CanonicalHashService hashService;

    public FilingRegistry(
            JdbcTemplate jdbc,
            FilingTextProcessor processor,
            CanonicalHashService hashService
    ) {
        this.jdbc = jdbc;
        this.processor = processor;
        this.hashService = hashService;
    }

    public void register(StoredSource source, JsonNode snapshot) {
        if (!"FILING".equals(source.purpose())) {
            throw new IllegalArgumentException("Only a FILING source can populate the filing registry");
        }
        JsonNode filings = snapshot.path("filings");
        if (!filings.isArray()) {
            throw new StepExecutionException(
                    "FILING_SNAPSHOT_INVALID",
                    "The filing snapshot does not contain a document collection",
                    false
            );
        }
        for (JsonNode filing : filings) {
            registerDocument(source, filing);
        }
    }

    private void registerDocument(StoredSource source, JsonNode filing) {
        String externalId = required(filing, "documentId");
        String formType = required(filing, "formType");
        LocalDate filingDate = LocalDate.parse(required(filing, "filingDate"));
        String title = required(filing, "title");
        String rawHtml = filing.path("contentHtml").asText();
        if (rawHtml.isBlank()) {
            rawHtml = "<html><body><h1>" + escape(title) + "</h1><p>"
                    + escape(required(filing, "summary")) + "</p></body></html>";
        }
        FilingTextProcessor.ProcessedFiling processed = processor.process(rawHtml);
        String rawHash = hashService.hashText(rawHtml);
        String cleanedHash = hashService.hashText(processed.cleanedText());
        UUID proposedId = UUID.randomUUID();
        jdbc.update("""
                insert into filings (
                    id, source_snapshot_id, external_document_id, accession_number,
                    form_type, filing_date, report_period, title, raw_text_uri,
                    raw_text_hash, cleaned_text_hash, parser_version, is_demo_data
                ) values (?, ?, ?, ?, ?, ?, ?, ?, null, ?, ?, ?, true)
                on conflict (source_snapshot_id, external_document_id) do nothing
                """,
                proposedId,
                source.id(),
                externalId,
                nullableText(filing, "accessionNumber"),
                formType,
                filingDate,
                nullableDate(filing, "reportPeriod"),
                title,
                rawHash,
                cleanedHash,
                FilingTextProcessor.PARSER_VERSION
        );
        UUID filingId = jdbc.queryForObject("""
                select id from filings
                 where source_snapshot_id = ? and external_document_id = ?
                """, UUID.class, source.id(), externalId);
        if (filingId == null) {
            throw new StepExecutionException(
                    "FILING_REGISTRY_WRITE_FAILED",
                    "The immutable filing record could not be registered",
                    true
            );
        }
        for (FilingTextProcessor.FilingChunk chunk : processed.chunks()) {
            jdbc.update("""
                    insert into filing_chunks (
                        id, filing_id, section_name, chunk_index, content,
                        content_hash, character_start, character_end, token_estimate
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (filing_id, section_name, chunk_index) do nothing
                    """,
                    UUID.randomUUID(),
                    filingId,
                    chunk.sectionName(),
                    chunk.index(),
                    chunk.content(),
                    hashService.hashText(chunk.content()),
                    chunk.characterStart(),
                    chunk.characterEnd(),
                    chunk.tokenEstimate()
            );
        }
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new StepExecutionException(
                    "FILING_DOCUMENT_INVALID",
                    "A filing document is missing required metadata",
                    false
            );
        }
        return value;
    }

    private static String nullableText(JsonNode node, String field) {
        String value = node.path(field).asText();
        return value.isBlank() ? null : value;
    }

    private static LocalDate nullableDate(JsonNode node, String field) {
        String value = nullableText(node, field);
        return value == null ? null : LocalDate.parse(value);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
