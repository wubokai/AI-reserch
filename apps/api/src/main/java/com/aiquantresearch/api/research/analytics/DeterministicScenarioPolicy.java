package com.aiquantresearch.api.research.analytics;

import com.aiquantresearch.api.research.provider.ScenarioAssumption;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class DeterministicScenarioPolicy {

    public static final String VERSION = "deterministic_scenario_policy_v1";

    private DeterministicScenarioPolicy() {
    }

    public static List<ScenarioAssumption> create(BigDecimal revenue, BigDecimal profit) {
        BigDecimal baseMargin = profit == null || revenue.signum() == 0
                ? new BigDecimal("0.20")
                : profit.divide(revenue, 8, RoundingMode.HALF_EVEN);
        baseMargin = clamp(baseMargin, new BigDecimal("-0.50"), new BigDecimal("0.80"));
        BigDecimal bullMargin = clamp(
                baseMargin.add(new BigDecimal("0.05")),
                new BigDecimal("-0.50"),
                new BigDecimal("0.80")
        );
        BigDecimal bearMargin = clamp(
                baseMargin.subtract(new BigDecimal("0.10")),
                new BigDecimal("-0.50"),
                new BigDecimal("0.80")
        );
        return List.of(
                new ScenarioAssumption(
                        "BULL", new BigDecimal("0.20"), bullMargin,
                        new BigDecimal("25"), new BigDecimal("0.25")
                ),
                new ScenarioAssumption(
                        "BASE", new BigDecimal("0.08"), baseMargin,
                        new BigDecimal("20"), new BigDecimal("0.50")
                ),
                new ScenarioAssumption(
                        "BEAR", new BigDecimal("-0.10"), bearMargin,
                        new BigDecimal("12"), new BigDecimal("0.25")
                )
        );
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal minimum, BigDecimal maximum) {
        return value.max(minimum).min(maximum);
    }
}
