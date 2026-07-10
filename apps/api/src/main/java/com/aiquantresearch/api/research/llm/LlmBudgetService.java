package com.aiquantresearch.api.research.llm;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LlmBudgetService {

    private final JdbcTemplate jdbc;
    private final LlmProperties properties;
    private final Clock clock;

    public LlmBudgetService(JdbcTemplate jdbc, LlmProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LlmBudgetReservation reserve(
            UUID researchId,
            UUID attemptId,
            String requestHash,
            BigDecimal requestedCostUsd,
            int requestedCallCount
    ) {
        if (requestedCostUsd == null) {
            throw new OpenAiResponseException(
                    "LLM_PRICING_UNKNOWN",
                    "A versioned price is required before reserving LLM budget",
                    false
            );
        }
        UUID locked = jdbc.queryForObject(
                "select id from research_jobs where id = ? for update",
                UUID.class,
                researchId
        );
        if (locked == null) {
            throw new OpenAiResponseException(
                    "LLM_RESEARCH_NOT_FOUND",
                    "The research budget boundary is unavailable",
                    false
            );
        }
        Instant now = clock.instant();
        jdbc.update("""
                update llm_budget_reservations
                   set status = 'RELEASED', updated_at = ?, settled_at = ?
                 where research_job_id = ? and status = 'RESERVED' and expires_at <= ?
                """, Timestamp.from(now), Timestamp.from(now), researchId, Timestamp.from(now));

        var existing = jdbc.query("""
                select id, reserved_cost_usd, reserved_call_count
                  from llm_budget_reservations
                 where step_attempt_id = ? and request_hash = ? and status = 'RESERVED'
                """, (row, ignored) -> new LlmBudgetReservation(
                row.getObject("id", UUID.class),
                row.getBigDecimal("reserved_cost_usd"),
                row.getInt("reserved_call_count")
        ), attemptId, requestHash);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }

        Integer completedCalls = jdbc.queryForObject("""
                select coalesce(sum(network_call_count), 0)
                  from llm_calls where research_job_id = ? and not is_mock
                """, Integer.class, researchId);
        Integer reservedCalls = jdbc.queryForObject("""
                select coalesce(sum(reserved_call_count), 0)
                  from llm_budget_reservations
                 where research_job_id = ? and status = 'RESERVED'
                """, Integer.class, researchId);
        BigDecimal spent = jdbc.queryForObject("""
                select coalesce(sum(estimated_cost_usd), 0)
                  from llm_calls where research_job_id = ? and not is_mock
                """, BigDecimal.class, researchId);
        BigDecimal reserved = jdbc.queryForObject("""
                select coalesce(sum(reserved_cost_usd), 0)
                  from llm_budget_reservations
                 where research_job_id = ? and status = 'RESERVED'
                """, BigDecimal.class, researchId);

        int calls = value(completedCalls) + value(reservedCalls) + requestedCallCount;
        BigDecimal committed = zero(spent).add(zero(reserved)).add(requestedCostUsd);
        if (requestedCallCount < 1
                || calls > properties.maxCalls()
                || committed.compareTo(properties.maxCostUsd()) > 0) {
            throw new OpenAiResponseException(
                    "LLM_BUDGET_EXCEEDED",
                    "The research LLM budget is exhausted before this call",
                    false
            );
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into llm_budget_reservations (
                    id, research_job_id, step_attempt_id, request_hash,
                    reserved_cost_usd, reserved_call_count, status,
                    expires_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, 'RESERVED', ?, ?, ?)
                """,
                id,
                researchId,
                attemptId,
                requestHash,
                requestedCostUsd,
                requestedCallCount,
                Timestamp.from(now.plus(properties.reservationTtl())),
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return new LlmBudgetReservation(id, requestedCostUsd, requestedCallCount);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID reservationId) {
        if (reservationId == null) {
            return;
        }
        Instant now = clock.instant();
        jdbc.update("""
                update llm_budget_reservations
                   set status = 'RELEASED', updated_at = ?, settled_at = ?
                 where id = ? and status = 'RESERVED'
                """, Timestamp.from(now), Timestamp.from(now), reservationId);
    }

    @Transactional
    public void settle(UUID reservationId, BigDecimal actualCostUsd, int actualCallCount) {
        if (reservationId == null) {
            return;
        }
        Instant now = clock.instant();
        int updated = jdbc.update("""
                update llm_budget_reservations
                   set status = 'SETTLED', actual_cost_usd = ?,
                       actual_call_count = ?, updated_at = ?, settled_at = ?
                 where id = ? and status = 'RESERVED'
                """, actualCostUsd, actualCallCount,
                Timestamp.from(now), Timestamp.from(now), reservationId);
        if (updated != 1) {
            throw new OpenAiResponseException(
                    "LLM_BUDGET_SETTLEMENT_FAILED",
                    "The LLM budget reservation could not be settled",
                    true
            );
        }
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
