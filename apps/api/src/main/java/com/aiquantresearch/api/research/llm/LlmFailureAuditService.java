package com.aiquantresearch.api.research.llm;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LlmFailureAuditService {

    private static final String PROVIDER = "OPENAI";

    private final JdbcTemplate jdbc;
    private final LlmProperties properties;

    public LlmFailureAuditService(JdbcTemplate jdbc, LlmProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            ResearchLanguageModelRequest request,
            PreparedLlmRequest prepared,
            LlmUsage usage,
            BigDecimal estimatedCostUsd,
            long latencyMs,
            String errorCode,
            String providerRequestId,
            int networkCallCount
    ) {
        if (networkCallCount < 1) {
            return;
        }
        String status = status(errorCode);
        jdbc.update("""
                insert into llm_calls (
                    id, research_job_id, step_attempt_id, provider, model_name,
                    prompt_version, schema_version, request_hash, response_hash,
                    input_tokens, output_tokens, cached_tokens, estimated_cost_usd,
                    latency_ms, status, error_code, is_mock,
                    provider_request_id, pricing_version, network_call_count
                ) values (?, ?, ?, ?, ?, ?, ?, ?, null, ?, ?, ?, ?, ?, ?, ?, false, ?, ?, ?)
                on conflict (step_attempt_id, request_hash) where step_attempt_id is not null
                do nothing
                """,
                UUID.randomUUID(),
                request.context().researchId(),
                request.attemptId(),
                PROVIDER,
                properties.reportModel(),
                properties.promptVersion(),
                properties.schemaVersion(),
                prepared.requestHash(),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cachedInputTokens(),
                estimatedCostUsd,
                latencyMs,
                status,
                errorCode,
                providerRequestId,
                properties.pricingVersion(),
                networkCallCount
        );
    }

    private static String status(String errorCode) {
        if ("LLM_REFUSED".equals(errorCode)) {
            return "REFUSED";
        }
        if (errorCode != null && errorCode.startsWith("LLM_INCOMPLETE_")) {
            return "INCOMPLETE";
        }
        return "FAILED";
    }
}
