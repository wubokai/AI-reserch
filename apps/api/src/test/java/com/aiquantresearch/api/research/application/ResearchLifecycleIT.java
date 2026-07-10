package com.aiquantresearch.api.research.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiquantresearch.api.research.domain.ReportDepth;
import com.aiquantresearch.api.research.domain.ResearchLocale;
import com.aiquantresearch.api.research.domain.ResearchPeriod;
import com.aiquantresearch.api.research.domain.ResearchStatus;
import com.aiquantresearch.api.research.domain.StepStatus;
import com.aiquantresearch.api.research.domain.StepType;
import com.aiquantresearch.api.research.persistence.ResearchJobEntity;
import com.aiquantresearch.api.support.PostgresRedisIntegrationTestSupport;
import jakarta.persistence.EntityManagerFactory;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ResourceLock("durable-postgres-queue")
class ResearchLifecycleIT extends PostgresRedisIntegrationTestSupport {

    private static final String OUTPUT_HASH = "b".repeat(64);
    private static final int CLAIM_PRIORITY = Integer.MAX_VALUE;

    @Autowired
    private ResearchCommandService commandService;

    @Autowired
    private ResearchQueryService queryService;

    @Autowired
    private ResearchWorkflowService workflowService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final Set<UUID> createdOwnerIds = new LinkedHashSet<>();
    private final Set<UUID> createdResearchIds = new LinkedHashSet<>();

    @AfterEach
    void deleteOnlyThisTestsRows() {
        Set<UUID> cleanupResearchIds = new LinkedHashSet<>(createdResearchIds);
        for (UUID ownerId : createdOwnerIds) {
            cleanupResearchIds.addAll(jdbcTemplate.queryForList(
                    "select id from research_jobs where user_id = ?",
                    UUID.class,
                    ownerId
            ));
        }

        for (UUID researchId : cleanupResearchIds) {
            jdbcTemplate.update("""
                    delete from outbox_events
                     where aggregate_id = ?
                        or aggregate_id in (
                            select id from research_steps where research_job_id = ?
                        )
                        or payload_json ->> 'researchJobId' = ?
                    """, researchId, researchId, researchId.toString());
            jdbcTemplate.update("delete from audit_events where research_job_id = ?", researchId);
            jdbcTemplate.update("delete from idempotency_records where resource_id = ?", researchId);
            jdbcTemplate.update("""
                    delete from step_attempts
                     where research_step_id in (
                         select id from research_steps where research_job_id = ?
                     )
                    """, researchId);
            jdbcTemplate.update("delete from research_steps where research_job_id = ?", researchId);
            jdbcTemplate.update("delete from research_jobs where id = ?", researchId);
        }

        for (UUID ownerId : createdOwnerIds) {
            jdbcTemplate.update("delete from idempotency_records where user_id = ?", ownerId);
            jdbcTemplate.update("delete from audit_events where actor_user_id = ?", ownerId);
            jdbcTemplate.update("delete from users where id = ?", ownerId);
        }
        createdResearchIds.clear();
        createdOwnerIds.clear();
    }

    @Test
    void createAtomicallyPersistsResearchPlanIdempotencyAuditAndOutbox() {
        UUID ownerId = UUID.randomUUID();
        String idempotencyKey = key("atomic-create");

        CreatedResearch created = create(ownerId, idempotencyKey, command("atomic persistence"));
        UUID researchId = created.researchId();

        assertThat(created.result().idempotencyReplayed()).isFalse();
        assertThat(created.result().value().status()).isEqualTo(ResearchStatus.QUEUED);

        Map<String, Object> research = jdbcTemplate.queryForMap("""
                select user_id, status, progress, current_step, deleted_at
                  from research_jobs
                 where id = ?
                """, researchId);
        assertThat(research)
                .containsEntry("user_id", ownerId)
                .containsEntry("status", ResearchStatus.QUEUED.name())
                .containsEntry("current_step", StepType.RESOLVE_SECURITY.name())
                .containsEntry("deleted_at", null);
        assertThat(((Number) research.get("progress")).intValue()).isZero();

        List<Map<String, Object>> steps = jdbcTemplate.queryForList("""
                select sequence_no, step_type, status, available_at
                  from research_steps
                 where research_job_id = ?
                 order by sequence_no
                """, researchId);
        assertThat(steps).hasSize(StepType.values().length);
        assertThat(steps.stream().map(row -> row.get("step_type")).toList())
                .containsExactlyElementsOf(Arrays.stream(StepType.values()).map(Enum::name).toList());
        assertThat(steps).allSatisfy(row -> assertThat(row.get("status"))
                .isEqualTo(StepStatus.PENDING.name()));
        assertThat(steps.getFirst().get("available_at")).isNotNull();
        assertThat(steps.subList(1, steps.size()))
                .allSatisfy(row -> assertThat(row.get("available_at")).isNull());

        Map<String, Object> idempotency = jdbcTemplate.queryForMap("""
                select status, response_status, resource_id
                  from idempotency_records
                 where user_id = ?
                   and http_method = 'POST'
                   and request_path = '/api/v1/research'
                   and idempotency_key = ?
                """, ownerId, idempotencyKey);
        assertThat(idempotency)
                .containsEntry("status", "COMPLETED")
                .containsEntry("resource_id", researchId);
        assertThat(((Number) idempotency.get("response_status")).intValue()).isEqualTo(202);

        assertThat(count("select count(*) from users where id = ?", ownerId)).isOne();
        assertThat(count("select count(*) from audit_events where research_job_id = ?", researchId))
                .isOne();
        assertThat(count("""
                select count(*) from outbox_events
                 where aggregate_type = 'RESEARCH' and aggregate_id = ?
                """, researchId)).isOne();
    }

    @Test
    void createReplayReturnsSameResearchWithoutDuplicatingRecordsAndRejectsChangedBody() {
        UUID ownerId = UUID.randomUUID();
        String idempotencyKey = key("replay-create");
        CreateResearchCommand command = command("idempotency replay");
        CreatedResearch first = create(ownerId, idempotencyKey, command);
        UUID researchId = first.researchId();

        CommandResult<ResearchAcceptedView> replay = trackedCreate(
                ownerId,
                idempotencyKey,
                command
        );

        assertThat(replay.idempotencyReplayed()).isTrue();
        assertThat(replay.value()).isEqualTo(first.result().value());
        assertThat(count("select count(*) from research_jobs where user_id = ?", ownerId)).isOne();
        assertThat(count("select count(*) from research_steps where research_job_id = ?", researchId))
                .isEqualTo(StepType.values().length);
        assertThat(count("select count(*) from idempotency_records where user_id = ?", ownerId)).isOne();
        assertThat(count("select count(*) from audit_events where research_job_id = ?", researchId))
                .isOne();
        assertThat(count("""
                select count(*) from outbox_events
                 where aggregate_type = 'RESEARCH' and aggregate_id = ?
                """, researchId)).isOne();

        assertThatThrownBy(() -> trackedCreate(
                ownerId,
                idempotencyKey,
                command("a materially different request body")
        )).isInstanceOf(IdempotencyKeyReusedException.class)
                .extracting(exception -> ((ResearchApplicationException) exception).code())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");

        assertThat(count("select count(*) from research_jobs where user_id = ?", ownerId)).isOne();
        assertThat(count("select count(*) from research_steps where research_job_id = ?", researchId))
                .isEqualTo(StepType.values().length);
        assertThat(count("select count(*) from idempotency_records where user_id = ?", ownerId)).isOne();
        assertThat(count("select count(*) from audit_events where research_job_id = ?", researchId))
                .isOne();
    }

    @Test
    void ownerScopingHidesReadsAndModificationsBehindResearchNotFound() {
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        CreatedResearch researchA = create(ownerA, key("owner-a"), command("owner A isolation"));
        CreatedResearch researchB = create(ownerB, key("owner-b"), command("owner B isolation"));
        UUID researchAId = researchA.researchId();

        assertThat(queryService.list(ownerB, ResearchListQuery.firstPage()).items())
                .extracting(ResearchItemView::researchId)
                .containsExactly(researchB.researchId());
        assertThatThrownBy(() -> queryService.detail(ownerB, researchAId))
                .isInstanceOf(ResearchNotFoundException.class);
        assertThatThrownBy(() -> queryService.status(ownerB, researchAId))
                .isInstanceOf(ResearchNotFoundException.class);
        assertThatThrownBy(() -> commandService.cancel(
                ownerB,
                researchAId,
                key("owner-b-cancel"),
                CancelResearchCommand.withoutReason()
        )).isInstanceOf(ResearchNotFoundException.class);
        assertThatThrownBy(() -> commandService.retry(
                ownerB,
                researchAId,
                key("owner-b-retry"),
                RetryResearchCommand.fromFirstFailedStep()
        )).isInstanceOf(ResearchNotFoundException.class);
        assertThatThrownBy(() -> commandService.softDelete(ownerB, researchAId))
                .isInstanceOf(ResearchNotFoundException.class);

        Map<String, Object> unchanged = jdbcTemplate.queryForMap("""
                select status, cancellation_requested, deleted_at
                  from research_jobs
                 where id = ?
                """, researchAId);
        assertThat(unchanged)
                .containsEntry("status", ResearchStatus.QUEUED.name())
                .containsEntry("cancellation_requested", false)
                .containsEntry("deleted_at", null);
        assertThat(count("""
                select count(*)
                  from idempotency_records
                 where user_id = ? and request_path like ?
                """, ownerB, "%/" + researchAId + "/%")).isZero();
        assertThat(count("select count(*) from audit_events where research_job_id = ?", researchAId))
                .isOne();
        assertThat(count("""
                select count(*) from outbox_events
                 where aggregate_type = 'RESEARCH' and aggregate_id = ?
                """, researchAId)).isOne();
    }

    @Test
    void queuedCancellationSettlesImmediatelyAndSoftDeleteIsHiddenAndRepeatable() {
        UUID ownerId = UUID.randomUUID();
        CreatedResearch created = create(ownerId, key("cancel-create"), command("cancel and delete"));
        UUID researchId = created.researchId();

        CommandResult<ResearchStatusView> cancelled = commandService.cancel(
                ownerId,
                researchId,
                key("cancel-command"),
                new CancelResearchCommand("  integration\n test cancellation  ")
        );

        assertThat(cancelled.idempotencyReplayed()).isFalse();
        assertThat(cancelled.value().status()).isEqualTo(ResearchStatus.CANCELLED);
        assertThat(cancelled.value().cancellationRequested()).isTrue();
        assertThat(cancelled.value().steps())
                .hasSize(StepType.values().length)
                .allSatisfy(step -> assertThat(step.status()).isEqualTo(StepStatus.CANCELLED));
        assertThat(count("""
                select count(*) from research_steps
                 where research_job_id = ? and status = 'CANCELLED'
                """, researchId)).isEqualTo(StepType.values().length);

        Map<String, Object> terminal = jdbcTemplate.queryForMap("""
                select status, cancellation_requested, cancellation_requested_at,
                       completed_at, current_step
                  from research_jobs
                 where id = ?
                """, researchId);
        assertThat(terminal)
                .containsEntry("status", ResearchStatus.CANCELLED.name())
                .containsEntry("cancellation_requested", true)
                .containsEntry("current_step", null);
        assertThat(terminal.get("cancellation_requested_at")).isNotNull();
        assertThat(terminal.get("completed_at")).isNotNull();

        commandService.softDelete(ownerId, researchId, "integration cleanup");
        assertThatCode(() -> commandService.softDelete(ownerId, researchId, "ignored duplicate"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> queryService.detail(ownerId, researchId))
                .isInstanceOf(ResearchNotFoundException.class);
        assertThatThrownBy(() -> queryService.status(ownerId, researchId))
                .isInstanceOf(ResearchNotFoundException.class);
        assertThat(queryService.list(ownerId, ResearchListQuery.firstPage()).items()).isEmpty();

        Map<String, Object> physicalRow = jdbcTemplate.queryForMap("""
                select status, deleted_at, deleted_by, delete_reason
                  from research_jobs
                 where id = ?
                """, researchId);
        assertThat(physicalRow)
                .containsEntry("status", ResearchStatus.CANCELLED.name())
                .containsEntry("deleted_by", ownerId)
                .containsEntry("delete_reason", "integration cleanup");
        assertThat(physicalRow.get("deleted_at")).isNotNull();
        assertThat(count("select count(*) from research_jobs where id = ?", researchId)).isOne();
        assertThat(jdbcTemplate.queryForList("""
                select action from audit_events
                 where research_job_id = ?
                 order by id
                """, String.class, researchId))
                .containsExactly("RESEARCH_CREATED", "CANCEL_REQUESTED", "SOFT_DELETED");
        assertThat(jdbcTemplate.queryForMap("""
                select
                    max(metadata_json ->> 'reason') filter (where action = 'CANCEL_REQUESTED')
                        as cancel_reason,
                    max(metadata_json ->> 'reason') filter (where action = 'SOFT_DELETED')
                        as delete_reason
                  from audit_events
                 where research_job_id = ?
                """, researchId))
                .containsEntry("cancel_reason", "integration test cancellation")
                .containsEntry("delete_reason", "integration cleanup");
        assertThat(jdbcTemplate.queryForList("""
                select event_type from outbox_events
                 where aggregate_type = 'RESEARCH' and aggregate_id = ?
                """, String.class, researchId))
                .containsExactlyInAnyOrder(
                        "RESEARCH_QUEUED",
                        "RESEARCH_CANCELLATION_REQUESTED",
                        "RESEARCH_SOFT_DELETED"
                );
        assertThat(jdbcTemplate.queryForObject("""
                select jsonb_array_length(payload_json -> 'changedSteps')
                  from outbox_events
                 where aggregate_type = 'RESEARCH'
                   and aggregate_id = ?
                   and event_type = 'RESEARCH_CANCELLATION_REQUESTED'
                """, Integer.class, researchId)).isEqualTo(StepType.values().length);
    }

    @Test
    void softDeleteSerializesBehindOwnerScopedLockWithoutOptimisticConflict() throws Exception {
        UUID ownerId = UUID.randomUUID();
        CreatedResearch created = create(ownerId, key("locked-delete-create"), command("locked delete"));
        UUID researchId = created.researchId();
        commandService.cancel(
                ownerId,
                researchId,
                key("locked-delete-cancel"),
                CancelResearchCommand.withoutReason()
        );

        var lockHeld = new CountDownLatch(1);
        var releaseLock = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var lockHolder = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        int updated = jdbcTemplate.update("""
                                update research_jobs
                                   set row_version = row_version + 1
                                 where id = ?
                                """, researchId);
                        if (updated != 1) {
                            throw new IllegalStateException("Expected to lock one research job");
                        }
                        lockHeld.countDown();
                        try {
                            if (!releaseLock.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Timed out holding research job lock");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Research job lock holder interrupted", exception);
                        }
                    }));

            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();
            var deletion = executor.submit(() -> commandService.softDelete(
                    ownerId,
                    researchId,
                    "serialized delete"
            ));

            awaitResearchJobLockWait();
            releaseLock.countDown();
            lockHolder.get(5, TimeUnit.SECONDS);
            deletion.get(5, TimeUnit.SECONDS);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jdbcTemplate.queryForMap("""
                select deleted_by, delete_reason
                  from research_jobs
                 where id = ?
                """, researchId))
                .containsEntry("deleted_by", ownerId)
                .containsEntry("delete_reason", "serialized delete");
        assertThat(count("""
                select count(*)
                  from audit_events
                 where research_job_id = ? and action = 'SOFT_DELETED'
                """, researchId)).isOne();
    }

    @Test
    void manualRetryReusesSuccessfulSameInputStepAndUnlocksFirstIncompleteStep() {
        UUID ownerId = UUID.randomUUID();
        CreatedResearch created = create(ownerId, key("retry-create"), command("manual retry"));
        UUID researchId = created.researchId();

        prioritize(researchId, StepType.RESOLVE_SECURITY);
        ClaimedStep resolvedClaim = claim(researchId, StepType.RESOLVE_SECURITY);
        Map<String, Object> completed = jdbcTemplate.queryForMap("""
                select *
                  from queue_v1.complete_step(?, ?, cast(? as varchar), cast(? as jsonb))
                """, resolvedClaim.attemptId(), resolvedClaim.leaseToken(), OUTPUT_HASH,
                "{\"artifact\":\"resolved-security\"}");
        assertThat(completed)
                .containsEntry("result_code", "SUCCEEDED")
                .containsEntry("research_job_id", researchId)
                .containsEntry("research_step_id", resolvedClaim.stepId());

        Map<String, Object> succeededBeforeRetry = jdbcTemplate.queryForMap("""
                select id, input_hash, successful_output_hash, attempt_count, max_attempts
                  from research_steps
                 where research_job_id = ? and step_type = 'RESOLVE_SECURITY'
                """, researchId);

        unlockAndPrioritize(researchId, StepType.FETCH_MARKET_DATA);
        ClaimedStep failedClaim = claim(researchId, StepType.FETCH_MARKET_DATA);
        Map<String, Object> failed = jdbcTemplate.queryForMap("""
                select *
                  from queue_v1.fail_step(
                      ?, ?, false, cast(? as varchar), cast(? as varchar), 1, 1
                  )
                """, failedClaim.attemptId(), failedClaim.leaseToken(),
                "INTEGRATION_FAILURE", "deterministic integration failure");
        assertThat(failed)
                .containsEntry("result_code", "FAILED")
                .containsEntry("step_status", StepStatus.FAILED.name())
                .containsEntry("research_job_id", researchId)
                .containsEntry("research_step_id", failedClaim.stepId());

        ResearchStatusView finalized = workflowService.finalizeResearch(
                researchId,
                false,
                false
        );
        assertThat(finalized.status()).isEqualTo(ResearchStatus.FAILED);
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_jobs where id = ?",
                String.class,
                researchId
        )).isEqualTo(ResearchStatus.FAILED.name());
        assertThat(jdbcTemplate.queryForList("""
                select status, skip_reason
                  from research_steps
                 where research_job_id = ?
                   and sequence_no > 2
                 order by sequence_no
                """, researchId))
                .hasSize(StepType.values().length - 2)
                .allSatisfy(step -> assertThat(step)
                        .containsEntry("status", StepStatus.SKIPPED.name())
                        .containsEntry(
                                "skip_reason",
                                "UPSTREAM_DEPENDENCY_UNSATISFIED"
                        ));

        CommandResult<ResearchAcceptedView> retried = commandService.retry(
                ownerId,
                researchId,
                key("manual-retry"),
                RetryResearchCommand.fromFirstFailedStep()
        );

        assertThat(retried.idempotencyReplayed()).isFalse();
        assertThat(retried.value().researchId()).isEqualTo(researchId);
        assertThat(retried.value().status()).isEqualTo(ResearchStatus.QUEUED);

        Map<String, Object> job = jdbcTemplate.queryForMap("""
                select status, progress, current_step, started_at, completed_at,
                       cancellation_requested
                  from research_jobs
                 where id = ?
                """, researchId);
        assertThat(job)
                .containsEntry("status", ResearchStatus.QUEUED.name())
                .containsEntry("current_step", StepType.FETCH_MARKET_DATA.name())
                .containsEntry("started_at", null)
                .containsEntry("completed_at", null)
                .containsEntry("cancellation_requested", false);
        assertThat(((Number) job.get("progress")).intValue())
                .isEqualTo(StepType.FETCH_MARKET_DATA.progress() - 1);

        Map<String, Object> succeededAfterRetry = jdbcTemplate.queryForMap("""
                select id, status, input_hash, successful_output_hash, attempt_count,
                       max_attempts, available_at
                  from research_steps
                 where research_job_id = ? and step_type = 'RESOLVE_SECURITY'
                """, researchId);
        assertThat(succeededAfterRetry)
                .containsEntry("id", succeededBeforeRetry.get("id"))
                .containsEntry("status", StepStatus.SUCCEEDED.name())
                .containsEntry("input_hash", succeededBeforeRetry.get("input_hash"))
                .containsEntry("successful_output_hash", succeededBeforeRetry.get("successful_output_hash"))
                .containsEntry("attempt_count", succeededBeforeRetry.get("attempt_count"))
                .containsEntry("max_attempts", succeededBeforeRetry.get("max_attempts"))
                .containsEntry("available_at", null);
        assertThat(count("""
                select count(*) from step_attempts
                 where research_step_id = ?
                """, resolvedClaim.stepId())).isOne();

        Map<String, Object> firstRunnable = jdbcTemplate.queryForMap("""
                select status, attempt_count, max_attempts, available_at
                  from research_steps
                 where research_job_id = ? and step_type = 'FETCH_MARKET_DATA'
                """, researchId);
        assertThat(firstRunnable)
                .containsEntry("status", StepStatus.PENDING.name())
                .containsEntry("attempt_count", 1)
                .containsEntry("max_attempts", 6);
        assertThat(firstRunnable.get("available_at")).isNotNull();
        assertThat(count("""
                select count(*) from research_steps
                 where research_job_id = ?
                   and status = 'PENDING'
                   and available_at is not null
                """, researchId)).isOne();
        assertThat(count("""
                select count(*) from research_steps
                 where research_job_id = ?
                   and sequence_no > 2
                   and available_at is not null
                """, researchId)).isZero();
        assertThat(count("select count(*) from research_steps where research_job_id = ?", researchId))
                .isEqualTo(StepType.values().length);
        assertThat(count("""
                select count(*) from step_attempts a
                  join research_steps s on s.id = a.research_step_id
                 where s.research_job_id = ?
                """, researchId)).isEqualTo(2);

        ResearchStatusView status = queryService.status(ownerId, researchId);
        assertThat(status.status()).isEqualTo(ResearchStatus.QUEUED);
        assertThat(status.currentStep()).isEqualTo(StepType.FETCH_MARKET_DATA);
        assertThat(status.steps().getFirst().status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(status.steps().get(1).status()).isEqualTo(StepStatus.PENDING);
        assertThat(jdbcTemplate.queryForList("""
                select action from audit_events
                 where research_job_id = ?
                 order by id
                """, String.class, researchId))
                .containsExactly(
                        "RESEARCH_CREATED",
                        "STATUS_CHANGED",
                        "RETRY_REQUESTED"
                );
        assertThat(jdbcTemplate.queryForObject("""
                select metadata_json ->> 'reason'
                  from audit_events
                 where research_job_id = ? and action = 'RETRY_REQUESTED'
                """, String.class, researchId)).isEqualTo("USER_REQUESTED");
        assertThat(jdbcTemplate.queryForList("""
                select event_type from outbox_events
                 where aggregate_type = 'RESEARCH' and aggregate_id = ?
                """, String.class, researchId))
                .containsExactlyInAnyOrder(
                        "RESEARCH_QUEUED",
                        "RESEARCH_FINALIZED",
                        "RESEARCH_RETRY_QUEUED"
                );
        assertThat(jdbcTemplate.queryForMap("""
                select
                    max(jsonb_array_length(payload_json -> 'changedSteps'))
                        filter (where event_type = 'RESEARCH_FINALIZED') as finalized_steps,
                    max(jsonb_array_length(payload_json -> 'changedSteps'))
                        filter (where event_type = 'RESEARCH_RETRY_QUEUED') as retried_steps
                  from outbox_events
                 where aggregate_type = 'RESEARCH' and aggregate_id = ?
                """, researchId))
                .containsEntry("finalized_steps", StepType.values().length - 2)
                .containsEntry("retried_steps", StepType.values().length - 1);
        assertThat(jdbcTemplate.queryForList("""
                select event_type
                  from outbox_events o
                  join research_steps s on s.id = o.aggregate_id
                 where o.aggregate_type = 'RESEARCH_STEP'
                   and s.research_job_id = ?
                """, String.class, researchId))
                .containsExactlyInAnyOrder(
                        "STEP_STARTED",
                        "STEP_SUCCEEDED",
                        "STEP_STARTED",
                        "STEP_FAILED"
                );
        assertThat(count("select count(*) from idempotency_records where user_id = ?", ownerId))
                .isEqualTo(2);

        assertThatThrownBy(() -> workflowService.projectStage(
                researchId,
                StepType.RESOLVE_SECURITY
        )).isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Illegal research transition");
        assertThat(jdbcTemplate.queryForMap("""
                select status, progress, current_step
                  from research_jobs
                 where id = ?
                """, researchId))
                .containsEntry("status", ResearchStatus.QUEUED.name())
                .containsEntry("progress", (int) StepType.FETCH_MARKET_DATA.progress() - 1)
                .containsEntry("current_step", StepType.FETCH_MARKET_DATA.name());

        ResearchStatusView resumed = workflowService.projectStage(
                researchId,
                StepType.FETCH_MARKET_DATA
        );
        assertThat(resumed.status()).isEqualTo(ResearchStatus.FETCHING_MARKET_DATA);
        assertThat(resumed.progress()).isEqualTo(StepType.FETCH_MARKET_DATA.progress());
        assertThat(resumed.currentStep()).isEqualTo(StepType.FETCH_MARKET_DATA);
        assertThat(jdbcTemplate.queryForMap("""
                select status, progress, current_step
                  from research_jobs
                 where id = ?
                """, researchId))
                .containsEntry("status", ResearchStatus.FETCHING_MARKET_DATA.name())
                .containsEntry("progress", (int) StepType.FETCH_MARKET_DATA.progress())
                .containsEntry("current_step", StepType.FETCH_MARKET_DATA.name());
    }

    @Test
    void flywayMigrationsAndJpaValidationHaveBootstrappedTheContext() {
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(entityManagerFactory.getMetamodel().entity(ResearchJobEntity.class)).isNotNull();
        assertThat(jdbcTemplate.queryForList("""
                select version
                  from flyway_schema_history
                 where success
                 order by installed_rank
                """, String.class)).contains("1", "2", "3", "4");
    }

    private CreatedResearch create(
            UUID ownerId,
            String idempotencyKey,
            CreateResearchCommand command
    ) {
        CommandResult<ResearchAcceptedView> result = trackedCreate(
                ownerId,
                idempotencyKey,
                command
        );
        return new CreatedResearch(result.value().researchId(), result);
    }

    private CommandResult<ResearchAcceptedView> trackedCreate(
            UUID ownerId,
            String idempotencyKey,
            CreateResearchCommand command
    ) {
        createdOwnerIds.add(ownerId);
        CommandResult<ResearchAcceptedView> result = commandService.create(
                ownerId,
                principal(ownerId),
                email(ownerId),
                idempotencyKey,
                command
        );
        if (result.value() != null && result.value().researchId() != null) {
            createdResearchIds.add(result.value().researchId());
        }
        return result;
    }

    private void prioritize(UUID researchId, StepType stepType) {
        assertThat(jdbcTemplate.update("""
                update research_steps
                   set priority = ?, row_version = row_version + 1
                 where research_job_id = ? and step_type = ? and status = 'PENDING'
                """, CLAIM_PRIORITY, researchId, stepType.name())).isOne();
    }

    private void unlockAndPrioritize(UUID researchId, StepType stepType) {
        assertThat(jdbcTemplate.update("""
                update research_steps
                   set priority = ?,
                       available_at = statement_timestamp(),
                       row_version = row_version + 1
                 where research_job_id = ? and step_type = ? and status = 'PENDING'
                """, CLAIM_PRIORITY, researchId, stepType.name())).isOne();
    }

    private ClaimedStep claim(UUID expectedResearchId, StepType stepType) {
        String sql = """
                select *
                  from queue_v1.claim_step(
                      cast(? as varchar),
                      ARRAY['%s']::varchar[],
                      60
                  )
                """.formatted(stepType.name());
        Map<String, Object> claimed = jdbcTemplate.queryForMap(
                sql,
                "research-lifecycle-it-" + UUID.randomUUID()
        );
        assertThat(claimed)
                .containsEntry("result_code", "CLAIMED")
                .containsEntry("research_job_id", expectedResearchId)
                .containsEntry("step_type", stepType.name());
        return new ClaimedStep(
                (UUID) claimed.get("research_step_id"),
                (UUID) claimed.get("attempt_id"),
                (UUID) claimed.get("lease_token")
        );
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        assertThat(value).isNotNull();
        return value;
    }

    private void awaitResearchJobLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbcTemplate.queryForObject("""
                    select count(*)
                      from pg_stat_activity
                     where pid <> pg_backend_pid()
                       and datname = current_database()
                       and wait_event_type = 'Lock'
                       and query ilike '%research_jobs%'
                    """, Long.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Soft delete never waited for the research job row lock");
    }

    private static CreateResearchCommand command(String marker) {
        return new CreateResearchCommand(
                "Integration lifecycle research for " + marker + " " + UUID.randomUUID(),
                "MU",
                null,
                ResearchLocale.EN_US,
                "SPY",
                ResearchPeriod.FIVE_YEARS,
                null,
                null,
                ReportDepth.STANDARD,
                true,
                true,
                true
        );
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String principal(UUID ownerId) {
        return "integration-user-" + ownerId;
    }

    private static String email(UUID ownerId) {
        return "it-" + ownerId.toString().replace("-", "") + "@local.invalid";
    }

    private record CreatedResearch(
            UUID researchId,
            CommandResult<ResearchAcceptedView> result
    ) {
    }

    private record ClaimedStep(UUID stepId, UUID attemptId, UUID leaseToken) {
    }
}
