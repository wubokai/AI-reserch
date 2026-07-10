package com.aiquantresearch.api.research.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiquantresearch.api.support.PostgresRedisIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ResourceLock("durable-postgres-queue")
class DurableQueueIT extends PostgresRedisIntegrationTestSupport {

    private static final String INPUT_HASH = "a".repeat(64);
    private static final String OUTPUT_HASH = "b".repeat(64);
    // Phase 3 artifact tests intentionally leave append-only jobs behind in the shared
    // Testcontainers database. Keep this fixture first in claim_step's documented order;
    // concurrent consumers may legitimately claim other runnable jobs after this one.
    private static final int TEST_PRIORITY = Integer.MAX_VALUE - 1;

    @Autowired
    private JdbcTemplate jdbc;
    private UUID ownerId;
    private final List<UUID> jobIds = new ArrayList<>();

    @BeforeEach
    void createIsolatedOwner() {
        ownerId = UUID.randomUUID();
        jdbc.update("insert into users (id, email) values (?, ?)",
                ownerId, "queue-" + ownerId + "@example.test");
    }

    @AfterEach
    void deleteOnlyThisTestsRows() {
        for (UUID jobId : jobIds) {
            jdbc.update("""
                    delete from outbox_events
                     where aggregate_id = ?
                        or aggregate_id in (
                            select id from research_steps where research_job_id = ?
                        )
                        or payload_json ->> 'researchJobId' = ?
                    """, jobId, jobId, jobId.toString());
            jdbc.update("delete from audit_events where research_job_id = ?", jobId);
            jdbc.update("""
                    delete from step_attempts
                     where research_step_id in (
                         select id from research_steps where research_job_id = ?
                     )
                    """, jobId);
            jdbc.update("delete from research_steps where research_job_id = ?", jobId);
            jdbc.update("delete from research_jobs where id = ?", jobId);
        }
        if (ownerId != null) {
            jdbc.update("delete from idempotency_records where user_id = ?", ownerId);
            jdbc.update("delete from audit_events where actor_user_id = ?", ownerId);
            jdbc.update("delete from users where id = ?", ownerId);
        }
        jobIds.clear();
        ownerId = null;
    }

    @RepeatedTest(3)
    void twentyConsumersCreateExactlyOneRunningAttempt() throws Exception {
        UUID jobId = createJob();
        UUID stepId = createStep(jobId, "RESOLVE_SECURITY", 1, TEST_PRIORITY, 3);
        int consumers = 20;
        var start = new CountDownLatch(1);
        List<Future<Claim>> claims = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(consumers)) {
            for (int index = 0; index < consumers; index++) {
                String workerId = "worker-" + index;
                claims.add(executor.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return claim(workerId, 60);
                }));
            }

            start.countDown();

            List<Claim> successfulClaims = new ArrayList<>();
            for (Future<Claim> claim : claims) {
                Claim value = claim.get(10, TimeUnit.SECONDS);
                if (value != null) {
                    successfulClaims.add(value);
                }
            }

            assertThat(successfulClaims)
                    .extracting(Claim::researchStepId)
                    .doesNotHaveDuplicates();
            List<Claim> fixtureClaims = successfulClaims.stream()
                    .filter(claim -> claim.researchJobId().equals(jobId))
                    .toList();
            assertThat(fixtureClaims).singleElement()
                    .satisfies(claim -> {
                        assertThat(claim.researchJobId()).isEqualTo(jobId);
                        assertThat(claim.researchStepId()).isEqualTo(stepId);
                    });
        }

        assertThat(count("select count(*) from step_attempts where research_step_id = ?", stepId))
                .isEqualTo(1);
        assertThat(count("""
                select count(*) from step_attempts
                where research_step_id = ? and status = 'RUNNING'
                """, stepId)).isEqualTo(1);
    }

    @Test
    @Transactional
    void securityDefinerFunctionsIgnoreCallerTemporaryTableShadows() {
        UUID jobId = createJob();
        UUID stepId = createStep(jobId, "RESOLVE_SECURITY", 1, TEST_PRIORITY, 3);
        jdbc.execute("""
                create temporary table outbox_events
                    (like public.outbox_events including all)
                on commit drop
                """);

        Claim claimed = claim("temp-shadow-worker", 60);

        assertThat(claimed).isNotNull();
        assertThat(claimed.researchStepId()).isEqualTo(stepId);
        assertThat(count("select count(*) from pg_temp.outbox_events")).isZero();
        assertThat(count("""
                select count(*) from public.outbox_events
                 where aggregate_type = 'RESEARCH_STEP'
                   and aggregate_id = ?
                   and event_type = 'STEP_STARTED'
                """, stepId)).isEqualTo(1);
    }

    @Test
    void expiredLeaseIsReapedAndOldTokenIsFenced() throws Exception {
        UUID jobId = createJob();
        UUID stepId = createStep(jobId, "RESOLVE_SECURITY", 1, TEST_PRIORITY, 2);
        Claim first = claim("expiring-worker", 5);

        assertThat(first).isNotNull();
        Heartbeat heartbeat = heartbeat(first, 5);
        assertThat(heartbeat.resultCode()).isEqualTo("HEARTBEAT_ACCEPTED");
        assertThat(heartbeat.cancellationRequested()).isFalse();
        assertThat(heartbeat.leaseExpiresAt()).isAfter(first.leaseExpiresAt());
        assertThat(result("""
                select result_code from queue_v1.checkpoint_step(?, ?, cast(? as jsonb))
                """, first.attemptId(), first.leaseToken(), "{\"phase\":\"safe\"}"))
                .isEqualTo("CHECKPOINT_ACCEPTED");

        Thread.sleep(Duration.ofMillis(5_200));

        assertThat(result("""
                select step_status from queue_v1.reap_expired(100, 1, 2)
                 where research_job_id = ?
                """, jobId)).isEqualTo("PENDING");
        assertThat(result("""
                select result_code from queue_v1.heartbeat(?, ?, 60)
                """, first.attemptId(), first.leaseToken())).isEqualTo("STALE_LEASE");
        assertThat(result("""
                select result_code from queue_v1.checkpoint_step(?, ?, '{}'::jsonb)
                """, first.attemptId(), first.leaseToken())).isEqualTo("STALE_LEASE");
        assertThat(result("""
                select result_code from queue_v1.fail_step(
                    ?, ?, true, 'LATE_FAILURE', 'Expired worker reported too late.', 1, 1
                )
                """, first.attemptId(), first.leaseToken())).isEqualTo("STALE_LEASE");
        assertThat(result("""
                select result_code from queue_v1.cancel_step(?, ?, '{}'::jsonb)
                """, first.attemptId(), first.leaseToken())).isEqualTo("STALE_LEASE");

        jdbc.update("""
                update research_steps
                   set available_at = statement_timestamp(), row_version = row_version + 1
                 where id = ?
                """, stepId);
        Claim second = claim("replacement-worker", 60);

        assertThat(second).isNotNull();
        assertThat(second.attemptId()).isNotEqualTo(first.attemptId());
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(result("""
                select result_code from queue_v1.complete_step(?, ?, ?, '{}'::jsonb)
                """, first.attemptId(), first.leaseToken(), OUTPUT_HASH)).isEqualTo("STALE_LEASE");
        assertThat(result("""
                select result_code from queue_v1.complete_step(?, ?, ?, '{}'::jsonb)
                """, second.attemptId(), second.leaseToken(), OUTPUT_HASH)).isEqualTo("SUCCEEDED");
        assertThat(result("""
                select result_code from queue_v1.complete_step(?, ?, ?, '{}'::jsonb)
                """, second.attemptId(), second.leaseToken(), OUTPUT_HASH))
                .isEqualTo("ALREADY_SUCCEEDED");
    }

    @Test
    void shortHeartbeatCannotReduceAnExistingLongLease() {
        UUID jobId = createJob();
        createStep(jobId, "RESOLVE_SECURITY", 1, TEST_PRIORITY, 2);
        Claim longLease = claim("long-lease-worker", 3_600);

        assertThat(longLease).isNotNull();
        Heartbeat shortHeartbeat = heartbeat(longLease, 5);

        assertThat(shortHeartbeat.resultCode()).isEqualTo("HEARTBEAT_ACCEPTED");
        assertThat(shortHeartbeat.leaseExpiresAt()).isAfterOrEqualTo(longLease.leaseExpiresAt());
        assertThat(jdbc.queryForObject("""
                select lease_expires_at >= ?
                  from step_attempts where id = ?
                """, Boolean.class, Timestamp.from(longLease.leaseExpiresAt()),
                longLease.attemptId())).isTrue();
    }

    @Test
    void cancellationStopsPendingAndRunningStepsWithoutPublishingSuccess() {
        UUID jobId = createJob();
        UUID runningStepId = createStep(jobId, "RESOLVE_SECURITY", 1, TEST_PRIORITY + 1, 3);
        UUID pendingStepId = createStep(jobId, "FETCH_MARKET_DATA", 2, TEST_PRIORITY, 3);
        Claim running = claim("cancelling-worker", 5);

        assertThat(running).isNotNull();
        assertThat(running.researchStepId()).isEqualTo(runningStepId);
        List<CancelRequestResult> cancellation = jdbc.query("""
                select result_code, cancellation_requested, cancelled_pending_steps
                  from queue_v1.request_cancel(?, ?, ?, ?)
                """, (rs, rowNumber) -> new CancelRequestResult(
                rs.getString("result_code"),
                rs.getBoolean("cancellation_requested"),
                rs.getInt("cancelled_pending_steps")
        ), jobId, ownerId, "req-cancel-it", "integration test");

        assertThat(cancellation).singleElement().satisfies(value -> {
            assertThat(value.resultCode()).isEqualTo("CANCELLATION_REQUESTED");
            assertThat(value.cancellationRequested()).isTrue();
            assertThat(value.cancelledPendingSteps()).isEqualTo(1);
        });
        assertThat(result("""
                select result_code from queue_v1.complete_step(?, ?, ?, '{}'::jsonb)
                """, running.attemptId(), running.leaseToken(), OUTPUT_HASH))
                .isEqualTo("CANCELLATION_REQUESTED");
        Heartbeat cancellingHeartbeat = heartbeat(running, 5);
        assertThat(cancellingHeartbeat.resultCode()).isEqualTo("HEARTBEAT_ACCEPTED");
        assertThat(cancellingHeartbeat.cancellationRequested()).isTrue();

        assertThatCode(() -> Thread.sleep(Duration.ofMillis(5_200))).doesNotThrowAnyException();
        assertThat(result("""
                select step_status from queue_v1.reap_expired(100, 1, 2)
                 where research_job_id = ?
                """, jobId)).isEqualTo("CANCELLED");
        assertThat(result("""
                select result_code from queue_v1.cancel_step(?, ?, cast(? as jsonb))
                """, running.attemptId(), running.leaseToken(), "{\"safe\":true}"))
                .isEqualTo("ALREADY_CANCELLED");

        assertThat(result("select status from research_steps where id = ?", runningStepId))
                .isEqualTo("CANCELLED");
        assertThat(result("select status from research_steps where id = ?", pendingStepId))
                .isEqualTo("CANCELLED");
        assertThat(result("select status from step_attempts where id = ?", running.attemptId()))
                .isEqualTo("CANCELLED");
        assertThat(count("select count(*) from audit_events where research_job_id = ?", jobId))
                .isEqualTo(1);
        assertThat(count("select count(*) from outbox_events where aggregate_id in (?, ?, ?)",
                jobId, runningStepId, pendingStepId)).isEqualTo(4);
    }

    @Test
    void nullAttemptOrLeaseTokenCannotCancelOrMutateRunningStep() {
        UUID jobId = createJob();
        UUID stepId = createStep(jobId, "RESOLVE_SECURITY", 1, TEST_PRIORITY, 3);
        Claim running = claim("null-fencing-worker", 60);

        assertThat(running).isNotNull();
        assertThatThrownBy(() -> jdbc.queryForObject("""
                select result_code
                  from queue_v1.cancel_step(?, NULL::uuid, NULL::jsonb)
                """, String.class, running.attemptId()))
                .hasMessageContaining("attempt_id and lease_token are required");
        assertThatThrownBy(() -> jdbc.queryForObject("""
                select result_code
                  from queue_v1.cancel_step(NULL::uuid, ?, NULL::jsonb)
                """, String.class, running.leaseToken()))
                .hasMessageContaining("attempt_id and lease_token are required");

        assertThat(result("select status from research_steps where id = ?", stepId))
                .isEqualTo("RUNNING");
        assertThat(result("select status from step_attempts where id = ?", running.attemptId()))
                .isEqualTo("RUNNING");
        assertThat(count("select count(*) from step_attempts where research_step_id = ?", stepId))
                .isEqualTo(1);
    }

    @Test
    void retryableFailureBacksOffAndAttemptBudgetEventuallyFailsStep() {
        UUID jobId = createJob();
        UUID stepId = createStep(jobId, "RESOLVE_SECURITY", 1, TEST_PRIORITY, 2);
        Claim first = claim("retry-worker-1", 60);

        assertThat(first).isNotNull();
        assertThat(result("""
                select result_code from queue_v1.fail_step(
                    ?, ?, true, 'PROVIDER_TIMEOUT', 'Provider did not respond.', 1, 1
                )
                """, first.attemptId(), first.leaseToken())).isEqualTo("RETRY_SCHEDULED");
        assertThat(result("select status from research_steps where id = ?", stepId))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
                select available_at > statement_timestamp()
                  from research_steps where id = ?
                """, Boolean.class, stepId)).isTrue();

        jdbc.update("""
                update research_steps
                   set available_at = statement_timestamp(), row_version = row_version + 1
                 where id = ?
                """, stepId);
        Claim second = claim("retry-worker-2", 60);

        assertThat(second).isNotNull();
        assertThat(second.attemptId()).isNotEqualTo(first.attemptId());
        assertThat(result("""
                select result_code from queue_v1.fail_step(
                    ?, ?, true, 'PROVIDER_TIMEOUT', 'Provider did not respond.', 1, 1
                )
                """, second.attemptId(), second.leaseToken())).isEqualTo("FAILED");
        assertThat(result("select status from research_steps where id = ?", stepId))
                .isEqualTo("FAILED");
        assertThat(count("select count(*) from step_attempts where research_step_id = ?", stepId))
                .isEqualTo(2);
    }

    private UUID createJob() {
        UUID jobId = UUID.randomUUID();
        jobIds.add(jobId);
        jdbc.update("""
                insert into research_jobs (
                    id, user_id, symbol_input, query, locale, request_json,
                    status, progress, current_step, data_mode, created_by, updated_by
                ) values (?, ?, 'MU', 'Analyze durable workflow behavior', 'en-US', '{}'::jsonb,
                          'QUEUED', 0, 'RESOLVE_SECURITY', 'MOCK', ?, ?)
                """, jobId, ownerId, ownerId, ownerId);
        return jobId;
    }

    private UUID createStep(
            UUID jobId,
            String stepType,
            int sequence,
            int priority,
            int maxAttempts
    ) {
        UUID stepId = UUID.randomUUID();
        jdbc.update("""
                insert into research_steps (
                    id, research_job_id, step_type, sequence_no, input_hash,
                    payload_version, payload_json, implementation_version,
                    priority, available_at, max_attempts, created_by, updated_by
                ) values (?, ?, ?, ?, ?, 1, '{}'::jsonb, 'queue-it-v1', ?,
                          statement_timestamp(), ?, ?, ?)
                """, stepId, jobId, stepType, sequence, INPUT_HASH, priority,
                maxAttempts, ownerId, ownerId);
        return stepId;
    }

    private Claim claim(String workerId, int leaseSeconds) {
        List<Claim> results = jdbc.query("""
                select research_job_id, research_step_id, attempt_id, lease_token, lease_expires_at
                  from queue_v1.claim_step(
                      cast(? as varchar), ARRAY['RESOLVE_SECURITY', 'FETCH_MARKET_DATA']::varchar[], ?
                  )
                """, (rs, rowNumber) -> new Claim(
                rs.getObject("research_job_id", UUID.class),
                rs.getObject("research_step_id", UUID.class),
                rs.getObject("attempt_id", UUID.class),
                rs.getObject("lease_token", UUID.class),
                rs.getObject("lease_expires_at", java.time.OffsetDateTime.class).toInstant()
        ), workerId, leaseSeconds);
        return results.isEmpty() ? null : results.getFirst();
    }

    private Heartbeat heartbeat(Claim claim, int leaseSeconds) {
        return jdbc.queryForObject("""
                select result_code, cancellation_requested, lease_expires_at
                  from queue_v1.heartbeat(?, ?, ?)
                """, (rs, rowNumber) -> new Heartbeat(
                rs.getString("result_code"),
                rs.getBoolean("cancellation_requested"),
                rs.getObject("lease_expires_at", java.time.OffsetDateTime.class).toInstant()
        ), claim.attemptId(), claim.leaseToken(), leaseSeconds);
    }

    private String result(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, String.class, arguments);
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private record Claim(
            UUID researchJobId,
            UUID researchStepId,
            UUID attemptId,
            UUID leaseToken,
            Instant leaseExpiresAt
    ) {
    }

    private record Heartbeat(
            String resultCode,
            boolean cancellationRequested,
            Instant leaseExpiresAt
    ) {
    }

    private record CancelRequestResult(
            String resultCode,
            boolean cancellationRequested,
            int cancelledPendingSteps
    ) {
    }
}
