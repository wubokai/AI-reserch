package com.aiquantresearch.api.research.filing;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class FilingChunkSearchService {

    private final JdbcTemplate jdbc;

    public FilingChunkSearchService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<FilingChunkMatch> search(UUID researchId, String query, int limit) {
        return jdbc.query("""
                select e.public_id as evidence_id,
                       f.external_document_id,
                       f.form_type,
                       f.filing_date,
                       fc.section_name,
                       fc.chunk_index,
                       replace(replace(
                           ts_headline(
                               'english', fc.content,
                               websearch_to_tsquery('english', ?),
                               'MaxWords=80, MinWords=20'
                           ),
                           '<b>', ''
                       ), '</b>', '') as excerpt,
                       'filing:' || f.external_document_id || '#'
                           || fc.section_name || ':chunk=' || fc.chunk_index
                           || ':chars=' || fc.character_start || '-' || fc.character_end
                           as citation_locator,
                       ts_rank_cd(
                           fc.search_vector,
                           websearch_to_tsquery('english', ?)
                       ) as rank
                  from filing_chunks fc
                  join filings f on f.id = fc.filing_id
                  join research_source_links rsl
                    on rsl.source_snapshot_id = f.source_snapshot_id
                   and rsl.purpose = 'FILING'
                  join evidence_items e
                    on e.research_job_id = rsl.research_job_id
                   and e.source_snapshot_id = f.source_snapshot_id
                 where rsl.research_job_id = ?
                   and fc.search_vector @@ websearch_to_tsquery('english', ?)
                 order by rank desc, f.filing_date desc,
                          fc.section_name, fc.chunk_index
                 limit ?
                """, (row, ignored) -> new FilingChunkMatch(
                row.getString("evidence_id"),
                row.getString("external_document_id"),
                row.getString("form_type"),
                row.getObject("filing_date", LocalDate.class),
                row.getString("section_name"),
                row.getInt("chunk_index"),
                row.getString("excerpt"),
                row.getString("citation_locator"),
                row.getDouble("rank")
        ), query, query, researchId, query, limit);
    }

    public record FilingChunkMatch(
            String evidenceId,
            String externalDocumentId,
            String formType,
            LocalDate filingDate,
            String sectionName,
            int chunkIndex,
            String excerpt,
            String citationLocator,
            double rank
    ) {
    }
}
