package com.aiquantresearch.api.research.retention;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ResearchRetentionSchedulerTest {

    @Test
    @SuppressWarnings("unchecked")
    void archivesExpiredTerminalResearchAndWritesSystemAudit() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        UUID researchId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(row.getObject("id", UUID.class)).thenReturn(researchId);
        when(row.getObject("user_id", UUID.class)).thenReturn(userId);
        when(jdbc.query(
                argThat(sql -> sql.contains("not legal_hold") && sql.contains("skip locked")),
                any(RowMapper.class),
                any(java.sql.Timestamp.class),
                eq(100)
        )).thenAnswer(invocation -> List.of(
                ((RowMapper<Object>) invocation.getArgument(1)).mapRow(row, 0)
        ));
        when(jdbc.update(
                argThat(sql -> sql.contains("R3_RETENTION_EXPIRED")),
                any(), any(), any(), eq(researchId)
        )).thenReturn(1);
        RetentionProperties properties = new RetentionProperties(
                true,
                1_095,
                365,
                90,
                100,
                Duration.ofMinutes(10),
                Duration.ofHours(24)
        );
        var scheduler = new ResearchRetentionScheduler(
                jdbc,
                properties,
                Clock.fixed(Instant.parse("2026-07-11T12:00:00Z"), ZoneOffset.UTC)
        );

        scheduler.archiveExpiredResearch();

        verify(jdbc).update(
                argThat(sql -> sql.contains("RESEARCH_RETENTION_ARCHIVED")),
                eq(researchId),
                eq(1_095),
                any(java.sql.Timestamp.class)
        );
    }
}
