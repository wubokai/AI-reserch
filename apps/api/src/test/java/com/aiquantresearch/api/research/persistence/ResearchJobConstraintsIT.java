package com.aiquantresearch.api.research.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiquantresearch.api.research.domain.StepType;
import com.aiquantresearch.api.support.PostgresRedisIntegrationTestSupport;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ResourceLock("durable-postgres-queue")
class ResearchJobConstraintsIT extends PostgresRedisIntegrationTestSupport {

    private static final String LIFECYCLE_CONSTRAINT =
            "ck_research_jobs_lifecycle_projection";
    private static final String CURRENT_STEP_CONSTRAINT =
            "ck_research_jobs_current_step";

    @Autowired
    private JdbcTemplate jdbc;

    private UUID ownerId;
    private final Set<UUID> insertedJobIds = new LinkedHashSet<>();

    @BeforeEach
    void createIsolatedOwner() {
        ownerId = UUID.randomUUID();
        jdbc.update(
                "insert into users (id, email) values (?, ?)",
                ownerId,
                "job-constraint-" + ownerId + "@example.test"
        );
    }

    @AfterEach
    void deleteOnlyThisTestsRows() {
        for (UUID jobId : insertedJobIds) {
            jdbc.update("delete from research_jobs where id = ?", jobId);
        }
        if (ownerId != null) {
            jdbc.update("delete from users where id = ?", ownerId);
        }
        insertedJobIds.clear();
        ownerId = null;
    }

    @Test
    void acceptsCreatedInitialQueueActiveProjectionManualRetryQueueAndSuccessTerminal() {
        insertValid("CREATED", 0, null, false);
        insertValid("QUEUED", 0, "RESOLVE_SECURITY", false);
        insertValid("FETCHING_MARKET_DATA", 15, "FETCH_MARKET_DATA", false);
        insertValid("FETCHING_MARKET_DATA", 24, "FETCH_MARKET_DATA", false);
        insertValid("QUEUED", 74, "ANALYZE_FUNDAMENTALS", false);
        insertValid("COMPLETED", 100, null, true);

        assertThat(jdbc.queryForObject(
                "select count(*) from research_jobs where user_id = ?",
                Integer.class,
                ownerId
        )).isEqualTo(6);
    }

    @Test
    void rejectsUnknownCurrentStepAtTheDatabaseBoundary() {
        assertConstraintRejected(
                CURRENT_STEP_CONSTRAINT,
                "QUEUED",
                0,
                "NOT_A_WORKFLOW_STEP",
                false
        );
    }

    @Test
    void rejectsStatusAndCurrentStepMismatchAtTheDatabaseBoundary() {
        assertConstraintRejected(
                LIFECYCLE_CONSTRAINT,
                "FETCHING_MARKET_DATA",
                15,
                "RESOLVE_SECURITY",
                false
        );
    }

    @Test
    void rejectsNonCanonicalActiveAndManualRetryProgressAtTheDatabaseBoundary() {
        assertConstraintRejected(
                LIFECYCLE_CONSTRAINT,
                "FETCHING_MARKET_DATA",
                14,
                "FETCH_MARKET_DATA",
                false
        );
        assertConstraintRejected(
                LIFECYCLE_CONSTRAINT,
                "QUEUED",
                75,
                "ANALYZE_FUNDAMENTALS",
                false
        );
    }

    @Test
    void rejectsMissingActiveStepAndIncoherentTerminalProgress() {
        assertConstraintRejected(LIFECYCLE_CONSTRAINT, "QUEUED", 0, null, false);
        assertConstraintRejected(LIFECYCLE_CONSTRAINT, "COMPLETED", 99, null, true);
        assertConstraintRejected(
                LIFECYCLE_CONSTRAINT,
                "PARTIALLY_COMPLETED",
                100,
                "VALIDATE_REPORT",
                true
        );
        assertConstraintRejected(LIFECYCLE_CONSTRAINT, "FAILED", 100, null, true);
    }

    @Test
    void queuedCheckpointProjectsOnlyItsStoredStageAtTheDatabaseBoundary() {
        UUID resumable = insert(
                "QUEUED",
                StepType.FETCH_MARKET_DATA.progress() - 1,
                StepType.FETCH_MARKET_DATA.name(),
                false
        );
        insertedJobIds.add(resumable);

        assertThat(jdbc.update("""
                update research_jobs
                   set status = 'FETCHING_MARKET_DATA',
                       progress = 15,
                       current_step = 'FETCH_MARKET_DATA',
                       row_version = row_version + 1
                 where id = ?
                """, resumable)).isOne();

        UUID arbitraryJump = insert(
                "QUEUED",
                StepType.FETCH_MARKET_DATA.progress() - 1,
                StepType.FETCH_MARKET_DATA.name(),
                false
        );
        insertedJobIds.add(arbitraryJump);

        assertThatThrownBy(() -> jdbc.update("""
                update research_jobs
                   set status = 'RESOLVING_SECURITY',
                       progress = 5,
                       current_step = 'RESOLVE_SECURITY',
                       row_version = row_version + 1
                 where id = ?
                """, arbitraryJump))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(failure -> {
                    Throwable cause = ((DataIntegrityViolationException) failure)
                            .getMostSpecificCause();
                    assertThat(cause)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining(
                                    "queued research must project its stored checkpoint"
                            );
                    assertThat(((SQLException) cause).getSQLState()).isEqualTo("23514");
                });
    }

    private void assertConstraintRejected(
            String constraintName,
            String status,
            int progress,
            String currentStep,
            boolean terminal
    ) {
        assertThatThrownBy(() -> insert(status, progress, currentStep, terminal))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(failure -> {
                    Throwable cause = ((DataIntegrityViolationException) failure)
                            .getMostSpecificCause();
                    assertThat(cause)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining(constraintName);
                    assertThat(((SQLException) cause).getSQLState()).isEqualTo("23514");
                });
    }

    private void insertValid(
            String status,
            int progress,
            String currentStep,
            boolean terminal
    ) {
        UUID jobId = insert(status, progress, currentStep, terminal);
        insertedJobIds.add(jobId);
    }

    private UUID insert(
            String status,
            int progress,
            String currentStep,
            boolean terminal
    ) {
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                insert into research_jobs (
                    id, user_id, symbol_input, query, locale, request_json,
                    status, progress, current_step, data_mode, started_at, completed_at,
                    created_by, updated_by
                ) values (
                    ?, ?, 'MU', 'Validate database lifecycle constraints', 'en-US',
                    '{}'::jsonb, ?, ?, ?, 'MOCK',
                    case when ? then statement_timestamp() else null end,
                    case when ? then statement_timestamp() else null end,
                    ?, ?
                )
                """,
                jobId,
                ownerId,
                status,
                progress,
                currentStep,
                terminal,
                terminal,
                ownerId,
                ownerId
        );
        return jobId;
    }
}
