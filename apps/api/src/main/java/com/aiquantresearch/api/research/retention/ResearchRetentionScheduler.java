package com.aiquantresearch.api.research.retention;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(
        name = "app.retention.enabled",
        havingValue = "true"
)
public class ResearchRetentionScheduler {

    private final JdbcTemplate jdbc;
    private final RetentionProperties properties;
    private final Clock clock;

    public ResearchRetentionScheduler(
            JdbcTemplate jdbc,
            RetentionProperties properties,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${app.retention.initial-delay:10m}",
            fixedDelayString = "${app.retention.sweep-delay:24h}"
    )
    @Transactional
    public void archiveExpiredResearch() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(
                java.time.Duration.ofDays(properties.researchArtifactDays())
        );
        List<Candidate> candidates = jdbc.query("""
                select id, user_id
                  from research_jobs
                 where deleted_at is null
                   and not legal_hold
                   and status in ('COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED', 'CANCELLED')
                   and created_at < ?
                 order by created_at, id
                 for update skip locked
                 limit ?
                """, (row, ignored) -> new Candidate(
                row.getObject("id", UUID.class),
                row.getObject("user_id", UUID.class)
        ), Timestamp.from(cutoff), properties.batchSize());

        for (Candidate candidate : candidates) {
            int updated = jdbc.update("""
                    update research_jobs
                       set deleted_at = ?, deleted_by = ?,
                           delete_reason = 'R3_RETENTION_EXPIRED',
                           updated_by = ?, row_version = row_version + 1
                     where id = ? and deleted_at is null and not legal_hold
                    """, Timestamp.from(now), candidate.userId(), candidate.userId(),
                    candidate.researchId());
            if (updated == 1) {
                jdbc.update("""
                        insert into audit_events (
                            research_job_id, actor_type, action, metadata_json, occurred_at
                        ) values (?, 'SYSTEM', 'RESEARCH_RETENTION_ARCHIVED',
                                  jsonb_build_object(
                                      'policy', 'R3_LONG_RESEARCH_V1',
                                      'retentionDays', ?
                                  ), ?)
                        """, candidate.researchId(), properties.researchArtifactDays(),
                        Timestamp.from(now));
            }
        }
    }

    private record Candidate(UUID researchId, UUID userId) {
    }
}
