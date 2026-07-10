package com.aiquantresearch.api.research.llm;

import java.math.BigDecimal;
import java.util.UUID;

public record LlmBudgetReservation(
        UUID id,
        BigDecimal reservedCostUsd,
        int reservedCallCount
) {
}
