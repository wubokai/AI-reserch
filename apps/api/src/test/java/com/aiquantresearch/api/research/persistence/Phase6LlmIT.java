package com.aiquantresearch.api.research.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiquantresearch.api.research.llm.LlmBudgetService;
import com.aiquantresearch.api.research.llm.LlmCallAudit;
import com.aiquantresearch.api.research.llm.LlmUsage;
import com.aiquantresearch.api.research.llm.OpenAiResponseException;
import com.aiquantresearch.api.research.orchestration.Phase3ArtifactStore;
import com.aiquantresearch.api.research.worker.QueueClaim;
import com.aiquantresearch.api.research.domain.StepType;
import com.aiquantresearch.api.support.PostgresRedisIntegrationTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ResourceLock("durable-postgres-queue")
class Phase6LlmIT extends PostgresRedisIntegrationTestSupport {

    private static final String INPUT_HASH = "a".repeat(64);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LlmBudgetService budgetService;

    @Autowired
    private Phase3ArtifactStore artifactStore;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID ownerId;
    private UUID researchId;

    @BeforeEach
    void createResearch() {
        ownerId = UUID.randomUUID();
        researchId = UUID.randomUUID();
        jdbc.update("insert into users (id, email) values (?, ?)",
                ownerId, "phase6-" + ownerId + "@example.test");
        jdbc.update("""
                insert into research_jobs (
                    id, user_id, security_id, symbol_input, query, locale, request_json,
                    status, progress, current_step, data_mode, started_at,
                    created_by, updated_by
                ) values (?, ?, '00000000-0000-4000-8000-000000000001', 'MU',
                          'Validate Phase 6 LLM budget', 'en-US', '{}'::jsonb,
                          'RUNNING', 80, 'GENERATE_REPORT', 'MOCK', statement_timestamp(),
                          ?, ?)
                """, researchId, ownerId, ownerId, ownerId);
    }

    @Test
    void migrationCreatesBudgetLedgerAndVersionedAuditColumns() {
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.tables
                 where table_schema = 'public' and table_name = 'llm_budget_reservations'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.columns
                 where table_schema = 'public' and table_name = 'llm_calls'
                   and column_name in ('provider_request_id', 'pricing_version')
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void reservesBeforeCallRejectsOverspendAndKeepsIdentityImmutable() {
        Attempt first = createAttempt(1);
        Attempt second = createAttempt(2);
        String firstHash = "b".repeat(64);
        String secondHash = "c".repeat(64);

        var reservation = budgetService.reserve(
                researchId,
                first.attemptId(),
                firstHash,
                new BigDecimal("0.75000000")
        );
        var replay = budgetService.reserve(
                researchId,
                first.attemptId(),
                firstHash,
                new BigDecimal("0.75000000")
        );

        assertThat(replay.id()).isEqualTo(reservation.id());
        assertThatThrownBy(() -> budgetService.reserve(
                researchId,
                second.attemptId(),
                secondHash,
                new BigDecimal("0.30000000")
        )).isInstanceOfSatisfying(OpenAiResponseException.class,
                exception -> assertThat(exception.code()).isEqualTo("LLM_BUDGET_EXCEEDED"));

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> jdbc.update("""
                update llm_budget_reservations set request_hash = ? where id = ?
                """, "d".repeat(64), reservation.id())))
                .hasMessageContaining("identity or transition is immutable");
    }

    @Test
    void persistsOneIdempotentAuditForTheSameAttemptAndRequest() {
        Attempt attempt = createAttempt(1);
        String requestHash = "e".repeat(64);
        LlmCallAudit audit = new LlmCallAudit(
                "OPENAI",
                "configured-model",
                "report_prompt_v1",
                "research_report_v1",
                requestHash,
                "f".repeat(64),
                new LlmUsage(100, 20, 10),
                new BigDecimal("0.00020000"),
                120,
                "SUCCEEDED",
                null,
                false,
                "resp_phase6",
                "pricing_2026_07_10",
                null
        );
        QueueClaim claim = new QueueClaim(
                researchId,
                attempt.stepId(),
                attempt.attemptId(),
                1,
                attempt.leaseToken(),
                Instant.now().plusSeconds(60),
                StepType.GENERATE_REPORT,
                INPUT_HASH,
                "phase6-it",
                1,
                objectMapper.createObjectNode()
        );

        UUID firstId = artifactStore.persistLlmCall(claim, audit);
        UUID replayId = artifactStore.persistLlmCall(claim, audit);

        assertThat(replayId).isEqualTo(firstId);
        assertThat(jdbc.queryForMap("""
                select provider_request_id, pricing_version from llm_calls where id = ?
                """, firstId))
                .containsEntry("provider_request_id", "resp_phase6")
                .containsEntry("pricing_version", "pricing_2026_07_10");
    }

    private Attempt createAttempt(int sequence) {
        UUID stepId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        jdbc.update("""
                insert into research_steps (
                    id, research_job_id, step_type, sequence_no, status,
                    input_hash, payload_version, payload_json, implementation_version,
                    priority, available_at, attempt_count, max_attempts,
                    created_by, updated_by
                ) values (?, ?, ?, ?, 'RUNNING', ?, 1, '{}'::jsonb, 'phase6-it',
                          0, null, 1, 3, ?, ?)
                """, stepId, researchId,
                sequence == 1 ? "GENERATE_REPORT" : "VALIDATE_REPORT",
                sequence, INPUT_HASH, ownerId, ownerId);
        jdbc.update("""
                insert into step_attempts (
                    id, research_step_id, attempt_number, status, retryable,
                    input_hash, worker_id, lease_token, lease_expires_at,
                    heartbeat_at, started_at
                ) values (?, ?, 1, 'RUNNING', false, ?, 'phase6-it', ?,
                          statement_timestamp() + interval '5 minutes',
                          statement_timestamp(), statement_timestamp())
                """, attemptId, stepId, INPUT_HASH, leaseToken);
        return new Attempt(stepId, attemptId, leaseToken);
    }

    private record Attempt(UUID stepId, UUID attemptId, UUID leaseToken) {
    }
}
