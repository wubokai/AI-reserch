package com.aiquantresearch.api.research.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiquantresearch.api.support.PostgresRedisIntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
class Phase3ArtifactsIT extends PostgresRedisIntegrationTestSupport {

    private static final String INITIAL_HASH = "a".repeat(64);
    private static final String OUTPUT_HASH = "b".repeat(64);
    private static final String OTHER_OUTPUT_HASH = "c".repeat(64);
    private static final AtomicInteger PRIORITY = new AtomicInteger(3_000_000);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;
    private UUID ownerId;

    @BeforeEach
    void createOwner() {
        transactions = new TransactionTemplate(transactionManager);
        ownerId = UUID.randomUUID();
        jdbc.update(
                "insert into users (id, email) values (?, ?)",
                ownerId,
                "phase3-" + ownerId + "@example.test"
        );
    }

    @Test
    void migrationSeedsAllFiveDeterministicMockSecurities() {
        assertThat(jdbc.queryForList("""
                select id::text, symbol
                  from securities
                 where id in (
                    '00000000-0000-4000-8000-000000000001',
                    '00000000-0000-4000-8000-000000000002',
                    '00000000-0000-4000-8000-000000000003',
                    '00000000-0000-4000-8000-000000000004',
                    '00000000-0000-4000-8000-000000000005'
                 )
                   and is_demo_data
                 order by id
                """))
                .extracting(row -> row.get("symbol"))
                .containsExactly("MU", "NVDA", "RKLB", "SPY", "QQQ");

        assertThat(jdbc.queryForObject("""
                select count(*)
                  from information_schema.tables
                 where table_schema = 'public'
                   and table_name in (
                       'source_snapshots', 'research_source_links', 'quant_results',
                       'evidence_items', 'llm_calls', 'report_versions', 'claims',
                       'claim_evidence_links', 'report_exports', 'research_run_manifests',
                       'market_price_bars', 'financial_metrics', 'macro_series'
                   )
                """, Integer.class)).isEqualTo(13);
    }

    @Test
    void completeAndAdvanceUsesCommittedOutputAndIsIdempotent() {
        UUID jobId = createJob("MOCK", "QUEUED", 0, "RESOLVE_SECURITY");
        UUID currentStepId = createPendingStep(
                jobId,
                "RESOLVE_SECURITY",
                1,
                "resolve-v1",
                1,
                true
        );
        UUID nextStepId = createPendingStep(
                jobId,
                "FETCH_MARKET_DATA",
                2,
                "market-v3",
                7,
                false
        );
        Claim claim = claimCurrentStep();

        AdvanceResult first = advance(claim, OUTPUT_HASH);
        String expectedNextInput = sha256(OUTPUT_HASH + ":market-v3:7");

        assertThat(first.resultCode()).isEqualTo("SUCCEEDED_AND_ADVANCED");
        assertThat(first.researchJobId()).isEqualTo(jobId);
        assertThat(first.researchStepId()).isEqualTo(currentStepId);
        assertThat(first.committedOutputHash()).isEqualTo(OUTPUT_HASH);
        assertThat(first.nextResearchStepId()).isEqualTo(nextStepId);
        assertThat(first.nextStepType()).isEqualTo("FETCH_MARKET_DATA");
        assertThat(first.nextInputHash()).isEqualTo(expectedNextInput);
        assertThat(jdbc.queryForObject(
                "select input_hash from research_steps where id = ?",
                String.class,
                nextStepId
        )).isEqualTo(expectedNextInput);
        assertThat(jdbc.queryForObject(
                "select available_at is not null from research_steps where id = ?",
                Boolean.class,
                nextStepId
        )).isTrue();

        AdvanceResult replay = advance(claim, OUTPUT_HASH);
        assertThat(replay.resultCode()).isEqualTo("ALREADY_ADVANCED");
        assertThat(replay.nextResearchStepId()).isEqualTo(nextStepId);
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox_events
                 where aggregate_id = ? and event_type = 'STEP_READY'
                """, Integer.class, nextStepId)).isEqualTo(1);

        assertThat(advance(claim, OTHER_OUTPUT_HASH).resultCode())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void manualRetrySuccessorWithAttemptHistoryIsRefreshedAndUnlocked() {
        UUID jobId = createJob("MOCK", "QUEUED", 0, "RESOLVE_SECURITY");
        createPendingStep(jobId, "RESOLVE_SECURITY", 1, "resolve-v1", 1, true);
        UUID retriedSuccessorId = createRetriedPendingStep(
                jobId,
                "FETCH_MARKET_DATA",
                2,
                "market-v3",
                7,
                2,
                6
        );
        Claim claim = claimCurrentStep();

        AdvanceResult advanced = advance(claim, OUTPUT_HASH);
        String expectedInputHash = sha256(OUTPUT_HASH + ":market-v3:7");

        assertThat(advanced.resultCode()).isEqualTo("SUCCEEDED_AND_ADVANCED");
        assertThat(advanced.nextResearchStepId()).isEqualTo(retriedSuccessorId);
        assertThat(advanced.nextInputHash()).isEqualTo(expectedInputHash);
        assertThat(jdbc.queryForMap("""
                select status, input_hash, attempt_count, max_attempts,
                       available_at is not null as is_available
                  from research_steps
                 where id = ?
                """, retriedSuccessorId))
                .containsEntry("status", "PENDING")
                .containsEntry("input_hash", expectedInputHash)
                .containsEntry("attempt_count", 2)
                .containsEntry("max_attempts", 6)
                .containsEntry("is_available", true);

        assertThat(advance(claim, OUTPUT_HASH).resultCode()).isEqualTo("ALREADY_ADVANCED");
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox_events
                 where aggregate_id = ? and event_type = 'STEP_READY'
                """, Integer.class, retriedSuccessorId)).isEqualTo(1);
    }

    @Test
    void retryInputRefreshGuardRejectsAnyHashNotDerivedFromSucceededPredecessor() {
        UUID jobId = createJob("MOCK", "QUEUED", 0, "RESOLVE_SECURITY");
        createSucceededStep(
                jobId,
                "RESOLVE_SECURITY",
                1,
                "resolve-v1",
                1,
                OUTPUT_HASH
        );
        UUID retriedSuccessorId = createRetriedPendingStep(
                jobId,
                "FETCH_MARKET_DATA",
                2,
                "market-v3",
                7,
                2,
                6
        );

        assertThatThrownBy(() -> jdbc.update("""
                update research_steps
                   set input_hash = ?,
                       available_at = statement_timestamp(),
                       row_version = row_version + 1
                 where id = ?
                """, OTHER_OUTPUT_HASH, retriedSuccessorId))
                .hasMessageContaining("claimed step execution inputs are immutable");

        assertThat(jdbc.queryForMap("""
                select input_hash, available_at
                  from research_steps
                 where id = ?
                """, retriedSuccessorId))
                .containsEntry("input_hash", INITIAL_HASH)
                .containsEntry("available_at", null);
    }

    @Test
    void advancementSkipsSkippedStepsAndLastStepDoesNotPublishResearch() {
        UUID jobId = createJob("MOCK", "QUEUED", 0, "RESOLVE_SECURITY");
        createPendingStep(jobId, "RESOLVE_SECURITY", 1, "resolve-v1", 1, true);
        createSkippedStep(jobId, "FETCH_MARKET_DATA", 2);
        UUID nextStepId = createPendingStep(
                jobId,
                "FETCH_FUNDAMENTALS",
                3,
                "fundamentals-v1",
                1,
                false
        );

        AdvanceResult advanced = advance(claimCurrentStep(), OUTPUT_HASH);
        assertThat(advanced.nextResearchStepId()).isEqualTo(nextStepId);
        assertThat(advanced.nextStepType()).isEqualTo("FETCH_FUNDAMENTALS");

        UUID lastJobId = createJob("MOCK", "QUEUED", 95, "VALIDATE_REPORT");
        createPendingStep(lastJobId, "VALIDATE_REPORT", 11, "report-validator-v1", 1, true);
        AdvanceResult last = advance(claimCurrentStep(), OUTPUT_HASH);

        assertThat(last.resultCode()).isEqualTo("SUCCEEDED_NO_SUCCESSOR");
        assertThat(last.nextResearchStepId()).isNull();
        assertThat(jdbc.queryForObject(
                "select status from research_jobs where id = ?",
                String.class,
                lastJobId
        )).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                "select latest_report_version_id from research_jobs where id = ?",
                UUID.class,
                lastJobId
        )).isNull();
    }

    @Test
    void wrongLeaseCannotCompleteOrUnlockSuccessor() {
        UUID jobId = createJob("MOCK", "QUEUED", 0, "RESOLVE_SECURITY");
        UUID currentStepId = createPendingStep(
                jobId,
                "RESOLVE_SECURITY",
                1,
                "resolve-v1",
                1,
                true
        );
        UUID nextStepId = createPendingStep(
                jobId,
                "FETCH_MARKET_DATA",
                2,
                "market-v1",
                1,
                false
        );
        Claim claim = claimCurrentStep();
        Claim wrongLease = new Claim(claim.attemptId(), UUID.randomUUID());

        assertThat(advance(wrongLease, OUTPUT_HASH).resultCode()).isEqualTo("STALE_LEASE");
        assertThat(jdbc.queryForObject(
                "select status from research_steps where id = ?",
                String.class,
                currentStepId
        )).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject(
                "select available_at is null from research_steps where id = ?",
                Boolean.class,
                nextStepId
        )).isTrue();
    }

    @Test
    void compositeForeignKeysRejectCrossResearchEvidenceLinks() {
        UUID firstJob = createJob("MOCK", "QUEUED", 0, "RESOLVE_SECURITY");
        UUID secondJob = createJob("MOCK", "QUEUED", 0, "RESOLVE_SECURITY");
        UUID firstReport = createFailedReport(firstJob, "MOCK");
        UUID firstClaim = createSupportingClaim(firstJob, firstReport);
        UUID secondSource = createSource(secondJob, true);
        UUID secondEvidence = createEvidence(secondJob, secondSource, true);

        assertThatThrownBy(() -> jdbc.update("""
                insert into claim_evidence_links (
                    claim_id, evidence_id, research_job_id,
                    support_role, relevance_score, citation_locator
                ) values (?, ?, ?, 'PRIMARY', 1, '/value')
                """, firstClaim, secondEvidence, firstJob))
                .hasMessageContaining("fk_claim_evidence_evidence_job");
    }

    @Test
    void reportEvidenceAndSourceArtifactsAreAppendOnly() {
        UUID jobId = createJob("MOCK", "QUEUED", 0, "RESOLVE_SECURITY");
        UUID sourceId = createSource(jobId, true);
        UUID evidenceId = createEvidence(jobId, sourceId, true);
        UUID reportId = createFailedReport(jobId, "MOCK");
        UUID claimId = createSupportingClaim(jobId, reportId);
        jdbc.update("""
                insert into claim_evidence_links (
                    claim_id, evidence_id, research_job_id,
                    support_role, relevance_score, citation_locator
                ) values (?, ?, ?, 'SUPPORTING', 0.9, '/value')
                """, claimId, evidenceId, jobId);

        assertImmutable("update source_snapshots set provider = 'changed' where id = ?", sourceId);
        assertImmutable("delete from source_snapshots where id = ?", sourceId);
        assertImmutable("update evidence_items set title = 'changed' where id = ?", evidenceId);
        assertImmutable("update report_versions set report_markdown = 'changed' where id = ?", reportId);
        assertImmutable("update claims set statement = 'changed' where id = ?", claimId);
        assertImmutable("""
                update claim_evidence_links set relevance_score = 0.1
                 where claim_id = ? and evidence_id = ?
                """, claimId, evidenceId);
    }

    @Test
    void normalizedProviderFactsEnforceResearchLineageUniquenessAndImmutability() {
        UUID jobId = createJob("MOCK", "QUEUED", 0, "RESOLVE_SECURITY");
        UUID sourceId = createSource(jobId, true);
        UUID marketId = UUID.randomUUID();
        UUID financialId = UUID.randomUUID();
        UUID macroId = UUID.randomUUID();

        jdbc.update("""
                insert into market_price_bars (
                    id, research_job_id, source_snapshot_id, security_id,
                    symbol, interval, observation_date, open, high, low,
                    close, adjusted_close, volume, provider, retrieved_at
                ) values (?, ?, ?, '00000000-0000-4000-8000-000000000001',
                          'MU', '1d', date '2023-12-29', 100, 102, 99,
                          101, 101, 1000000, 'MOCK_V1', statement_timestamp())
                """, marketId, jobId, sourceId);
        jdbc.update("""
                insert into financial_metrics (
                    id, research_job_id, source_snapshot_id, security_id,
                    symbol, fiscal_period, fiscal_year, period_end_date,
                    metric_name, metric_value, unit, provider, retrieved_at
                ) values (?, ?, ?, '00000000-0000-4000-8000-000000000001',
                          'MU', 'FY', 2023, date '2023-08-31',
                          'revenue', 1000, 'USD', 'MOCK_V1', statement_timestamp())
                """, financialId, jobId, sourceId);
        jdbc.update("""
                insert into macro_series (
                    id, research_job_id, source_snapshot_id, series_id,
                    series_name, observation_date, metric_value, unit,
                    provider, retrieved_at
                ) values (?, ?, ?, 'DFF', 'Federal Funds Rate', date '2023-12-29',
                          5.33, 'Percent', 'MOCK_V1', statement_timestamp())
                """, macroId, jobId, sourceId);

        assertThat(jdbc.queryForObject(
                "select count(*) from market_price_bars where research_job_id = ?",
                Integer.class,
                jobId
        )).isOne();
        assertThat(jdbc.queryForObject(
                "select count(*) from financial_metrics where research_job_id = ?",
                Integer.class,
                jobId
        )).isOne();
        assertThat(jdbc.queryForObject(
                "select count(*) from macro_series where research_job_id = ?",
                Integer.class,
                jobId
        )).isOne();

        assertThat(jdbc.update("""
                insert into market_price_bars (
                    research_job_id, source_snapshot_id, security_id,
                    symbol, interval, observation_date, open, high, low,
                    close, adjusted_close, volume, provider, retrieved_at
                ) values (?, ?, '00000000-0000-4000-8000-000000000001',
                          'MU', '1d', date '2023-12-29', 100, 102, 99,
                          101, 101, 1000000, 'MOCK_V1', statement_timestamp())
                on conflict (
                    research_job_id, source_snapshot_id, symbol, interval, observation_date
                ) do nothing
                """, jobId, sourceId)).isZero();

        UUID otherJob = createJob("MOCK", "QUEUED", 0, "RESOLVE_SECURITY");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> jdbc.update("""
                insert into market_price_bars (
                    research_job_id, source_snapshot_id, symbol, interval,
                    observation_date, open, high, low, close, adjusted_close,
                    volume, provider, retrieved_at
                ) values (?, ?, 'MU', '1d', date '2023-12-28', 100, 102, 99,
                          101, 101, 1000000, 'MOCK_V1', statement_timestamp())
                """, otherJob, sourceId)))
                .hasMessageContaining("source is not linked to this research job");

        assertImmutable("update market_price_bars set close = 99 where id = ?", marketId);
        assertImmutable("delete from financial_metrics where id = ?", financialId);
        assertImmutable("update macro_series set metric_value = 4 where id = ?", macroId);
    }

    @Test
    void mixedTestCannotPublishOrExport() {
        UUID jobId = createJob("MIXED_TEST", "QUEUED", 0, "RESOLVE_SECURITY");

        assertThatThrownBy(() -> createReport(jobId, "MIXED_TEST", "PASSED"))
                .hasMessageContaining("MIXED_TEST reports cannot be published");

        UUID failedReport = createFailedReport(jobId, "MIXED_TEST");
        assertThatThrownBy(() -> jdbc.update("""
                insert into report_exports (
                    report_version_id, research_job_id, format,
                    template_version, status
                ) values (?, ?, 'MARKDOWN', 'template-v1', 'PENDING')
                """, failedReport, jobId))
                .hasMessageContaining("MIXED_TEST reports cannot be exported");
    }

    @Test
    void validatedReportClaimsManifestAndTerminalStateCanPublishAtomically() {
        UUID jobId = createJob("MOCK", "VALIDATING_REPORT", 96, "VALIDATE_REPORT");
        UUID sourceId = createSource(jobId, true);
        UUID evidenceId = createEvidence(jobId, sourceId, true);
        UUID reportId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();

        transactions.executeWithoutResult(status -> {
            insertReport(reportId, jobId, "MOCK", "PASSED");
            insertClaim(claimId, jobId, reportId, "MATERIAL");
            jdbc.update("""
                    insert into claim_evidence_links (
                        claim_id, evidence_id, research_job_id,
                        support_role, relevance_score, citation_locator
                    ) values (?, ?, ?, 'PRIMARY', 1, '/value')
                    """, claimId, evidenceId, jobId);
            jdbc.update("""
                    insert into research_run_manifests (
                        research_job_id, execution_cycle, report_version_id,
                        manifest_json, content_hash, completion_policy_version,
                        data_mode, status
                    ) values (?, 1, ?, '{}'::jsonb, ?, 'completion-policy-v1',
                              'MOCK', 'PUBLISHED')
                    """, jobId, reportId, "f".repeat(64));
            jdbc.update("""
                    update research_jobs
                       set latest_report_version_id = ?,
                           status = 'COMPLETED',
                           progress = 100,
                           current_step = null,
                           completed_at = statement_timestamp(),
                           row_version = row_version + 1
                     where id = ?
                    """, reportId, jobId);
        });

        assertThat(jdbc.queryForObject(
                "select latest_report_version_id from research_jobs where id = ?",
                UUID.class,
                jobId
        )).isEqualTo(reportId);
        assertThat(jdbc.queryForObject(
                "select status from research_jobs where id = ?",
                String.class,
                jobId
        )).isEqualTo("COMPLETED");
    }

    private UUID createJob(
            String dataMode,
            String status,
            int progress,
            String currentStep
    ) {
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                insert into research_jobs (
                    id, user_id, security_id, symbol_input, query, locale, request_json,
                    status, progress, current_step, data_mode, created_by, updated_by
                ) values (?, ?, '00000000-0000-4000-8000-000000000001', 'MU',
                          'Analyze Phase 3 artifact integrity', 'en-US', '{}'::jsonb,
                          ?, ?, ?, ?, ?, ?)
                """, jobId, ownerId, status, progress, currentStep, dataMode, ownerId, ownerId);
        return jobId;
    }

    private UUID createPendingStep(
            UUID jobId,
            String stepType,
            int sequence,
            String implementationVersion,
            int payloadVersion,
            boolean available
    ) {
        UUID stepId = UUID.randomUUID();
        jdbc.update("""
                insert into research_steps (
                    id, research_job_id, step_type, sequence_no, input_hash,
                    payload_version, payload_json, implementation_version,
                    priority, available_at, max_attempts, created_by, updated_by
                ) values (?, ?, ?, ?, ?, ?, '{}'::jsonb, ?, ?,
                          case when ? then statement_timestamp() else null end,
                          3, ?, ?)
                """, stepId, jobId, stepType, sequence, INITIAL_HASH, payloadVersion,
                implementationVersion, PRIORITY.incrementAndGet(), available, ownerId, ownerId);
        return stepId;
    }

    private UUID createSkippedStep(UUID jobId, String stepType, int sequence) {
        UUID stepId = UUID.randomUUID();
        jdbc.update("""
                insert into research_steps (
                    id, research_job_id, step_type, sequence_no, status,
                    input_hash, payload_version, payload_json, implementation_version,
                    priority, available_at, max_attempts, skip_reason,
                    created_by, updated_by
                ) values (?, ?, ?, ?, 'SKIPPED', ?, 1, '{}'::jsonb,
                          'skipped-v1', ?, null, 3, 'DISABLED_BY_PLAN', ?, ?)
                """, stepId, jobId, stepType, sequence, INITIAL_HASH,
                PRIORITY.incrementAndGet(), ownerId, ownerId);
        return stepId;
    }

    private UUID createRetriedPendingStep(
            UUID jobId,
            String stepType,
            int sequence,
            String implementationVersion,
            int payloadVersion,
            int attemptCount,
            int maxAttempts
    ) {
        UUID stepId = UUID.randomUUID();
        jdbc.update("""
                insert into research_steps (
                    id, research_job_id, step_type, sequence_no, input_hash,
                    payload_version, payload_json, implementation_version,
                    priority, available_at, attempt_count, max_attempts,
                    created_by, updated_by
                ) values (?, ?, ?, ?, ?, ?, '{}'::jsonb, ?, ?, null, ?, ?, ?, ?)
                """, stepId, jobId, stepType, sequence, INITIAL_HASH, payloadVersion,
                implementationVersion, PRIORITY.incrementAndGet(), attemptCount,
                maxAttempts, ownerId, ownerId);
        return stepId;
    }

    private UUID createSucceededStep(
            UUID jobId,
            String stepType,
            int sequence,
            String implementationVersion,
            int payloadVersion,
            String outputHash
    ) {
        UUID stepId = UUID.randomUUID();
        jdbc.update("""
                insert into research_steps (
                    id, research_job_id, step_type, sequence_no, status,
                    input_hash, successful_output_hash, payload_version,
                    payload_json, implementation_version, priority,
                    available_at, attempt_count, max_attempts,
                    created_by, updated_by
                ) values (?, ?, ?, ?, 'SUCCEEDED', ?, ?, ?, '{}'::jsonb,
                          ?, ?, null, 1, 3, ?, ?)
                """, stepId, jobId, stepType, sequence, INITIAL_HASH, outputHash,
                payloadVersion, implementationVersion, PRIORITY.incrementAndGet(),
                ownerId, ownerId);
        return stepId;
    }

    private Claim claimCurrentStep() {
        return jdbc.queryForObject("""
                select attempt_id, lease_token
                  from queue_v1.claim_step(
                      'phase3-artifacts-it',
                      ARRAY[
                          'RESOLVE_SECURITY', 'FETCH_MARKET_DATA', 'FETCH_FUNDAMENTALS',
                          'FETCH_FILINGS', 'FETCH_MACRO_DATA', 'VALIDATE_DATA',
                          'RUN_QUANT_ANALYSIS', 'ANALYZE_FUNDAMENTALS', 'BUILD_EVIDENCE',
                          'GENERATE_REPORT', 'VALIDATE_REPORT'
                      ]::varchar[],
                      60
                  )
                """, (rs, rowNumber) -> new Claim(
                rs.getObject("attempt_id", UUID.class),
                rs.getObject("lease_token", UUID.class)
        ));
    }

    private AdvanceResult advance(Claim claim, String outputHash) {
        return jdbc.queryForObject("""
                select result_code, research_job_id, research_step_id,
                       committed_output_hash, next_research_step_id,
                       next_step_type, next_input_hash
                  from queue_v1.complete_step_and_advance(
                      ?, ?, ?, '{"fixture":"phase3"}'::jsonb
                  )
                """, (rs, rowNumber) -> new AdvanceResult(
                rs.getString("result_code"),
                rs.getObject("research_job_id", UUID.class),
                rs.getObject("research_step_id", UUID.class),
                rs.getString("committed_output_hash"),
                rs.getObject("next_research_step_id", UUID.class),
                rs.getString("next_step_type"),
                rs.getString("next_input_hash")
        ), claim.attemptId(), claim.leaseToken(), outputHash);
    }

    private UUID createSource(UUID jobId, boolean isDemoData) {
        UUID sourceId = UUID.randomUUID();
        String rawHash = sha256("raw:" + sourceId);
        String normalizedHash = sha256("normalized:" + sourceId);
        jdbc.update("""
                insert into source_snapshots (
                    id, provider, source_type, retrieved_at, effective_date,
                    raw_data_hash, normalized_data_hash, payload_json,
                    is_primary_source, freshness_status, is_demo_data, schema_version
                ) values (?, 'MOCK_V1', 'MOCK', statement_timestamp(), date '2026-06-30',
                          ?, ?, '{}'::jsonb, true, 'FRESH', ?, 'mock_source_v1')
                """, sourceId, rawHash, normalizedHash, isDemoData);
        jdbc.update("""
                insert into research_source_links (
                    research_job_id, source_snapshot_id, purpose
                ) values (?, ?, 'MARKET_DATA')
                """, jobId, sourceId);
        return sourceId;
    }

    private UUID createEvidence(UUID jobId, UUID sourceId, boolean isDemoData) {
        UUID evidenceId = UUID.randomUUID();
        jdbc.update("""
                insert into evidence_items (
                    id, public_id, research_job_id, source_snapshot_id,
                    evidence_type, title, summary, value_json, unit,
                    quality_score, is_demo_data
                ) values (?, ?, ?, ?, 'MARKET_PRICE', 'Mock price',
                          'Fixed mock price evidence', '{"value":"100.00"}'::jsonb,
                          'USD', 1, ?)
                """, evidenceId, "ev_" + compact(evidenceId), jobId, sourceId, isDemoData);
        return evidenceId;
    }

    private UUID createFailedReport(UUID jobId, String dataMode) {
        return createReport(jobId, dataMode, "FAILED");
    }

    private UUID createReport(UUID jobId, String dataMode, String validationStatus) {
        UUID reportId = UUID.randomUUID();
        insertReport(reportId, jobId, dataMode, validationStatus);
        return reportId;
    }

    private void insertReport(
            UUID reportId,
            UUID jobId,
            String dataMode,
            String validationStatus
    ) {
        jdbc.update("""
                insert into report_versions (
                    id, research_job_id, version, report_schema_version,
                    report_json, report_markdown, validation_status, content_hash,
                    data_mode, data_as_of_date, generated_at
                ) values (?, ?, 1, 'research_report_v1',
                          jsonb_build_object(
                              'schemaVersion', 'research_report_v1',
                              'dataMode', cast(? as text)
                          ),
                          'DEMO DATA — NOT REAL MARKET DATA', ?, ?, ?,
                          date '2026-06-30', statement_timestamp())
                """, reportId, jobId, dataMode, validationStatus,
                sha256("report:" + reportId), dataMode);
    }

    private UUID createSupportingClaim(UUID jobId, UUID reportId) {
        UUID claimId = UUID.randomUUID();
        insertClaim(claimId, jobId, reportId, "SUPPORTING");
        return claimId;
    }

    private void insertClaim(
            UUID claimId,
            UUID jobId,
            UUID reportId,
            String materiality
    ) {
        jdbc.update("""
                insert into claims (
                    id, public_id, report_version_id, research_job_id,
                    claim_key, claim_type, statement, materiality, confidence,
                    validation_status
                ) values (?, ?, ?, ?, ?, 'FACT', 'Mock fixture fact', ?, 1, 'PASSED')
                """, claimId, "cl_" + compact(claimId), reportId, jobId,
                "claim." + compact(claimId), materiality);
    }

    private void assertImmutable(String sql, Object... arguments) {
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                status -> jdbc.update(sql, arguments)
        )).hasMessageContaining("append-only");
    }

    private static String compact(UUID id) {
        return id.toString().replace("-", "");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM", exception);
        }
    }

    private record Claim(UUID attemptId, UUID leaseToken) {
    }

    private record AdvanceResult(
            String resultCode,
            UUID researchJobId,
            UUID researchStepId,
            String committedOutputHash,
            UUID nextResearchStepId,
            String nextStepType,
            String nextInputHash
    ) {
    }
}
