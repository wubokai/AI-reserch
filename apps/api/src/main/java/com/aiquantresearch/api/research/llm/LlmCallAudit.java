package com.aiquantresearch.api.research.llm;

import java.math.BigDecimal;
import java.util.UUID;

public record LlmCallAudit(
        String provider,
        String modelName,
        String promptVersion,
        String schemaVersion,
        String requestHash,
        String responseHash,
        LlmUsage usage,
        BigDecimal estimatedCostUsd,
        long latencyMs,
        String status,
        String errorCode,
        boolean mock,
        String providerRequestId,
        String pricingVersion,
        UUID budgetReservationId,
        int networkCallCount
) {

    public LlmCallAudit {
        if (provider == null || provider.isBlank()
                || modelName == null || modelName.isBlank()
                || promptVersion == null || promptVersion.isBlank()
                || schemaVersion == null || schemaVersion.isBlank()
                || requestHash == null || !requestHash.matches("^[0-9a-f]{64}$")
                || responseHash == null || !responseHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("LLM audit identity is invalid");
        }
        if (usage == null || latencyMs < 0 || networkCallCount < 0
                || (!mock && networkCallCount < 1)
                || !"SUCCEEDED".equals(status) || errorCode != null) {
            throw new IllegalArgumentException("Only successful generation audits can be published");
        }
    }
}
