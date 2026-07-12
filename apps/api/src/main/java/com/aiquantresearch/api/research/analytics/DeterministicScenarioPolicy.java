package com.aiquantresearch.api.research.analytics;

import com.aiquantresearch.api.research.provider.ScenarioAssumption;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class DeterministicScenarioPolicy {

    public static final String VERSION = "deterministic_scenario_policy_v2";

    private DeterministicScenarioPolicy() {
    }

    public static List<ScenarioAssumption> create(BigDecimal revenue, BigDecimal profit) {
        return create(revenue, profit, null, null, null);
    }

    public static List<ScenarioAssumption> create(
            BigDecimal revenue,
            BigDecimal profit,
            BigDecimal currentPrice,
            BigDecimal dilutedShares,
            BigDecimal netDebt
    ) {
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
        boolean anchored = currentPrice != null && dilutedShares != null && netDebt != null
                && currentPrice.signum() > 0 && dilutedShares.signum() > 0
                && revenue.signum() > 0;
        BigDecimal enterpriseValue = anchored
                ? currentPrice.multiply(dilutedShares).add(netDebt)
                : null;
        boolean useRevenueMultiple = profit == null || profit.signum() <= 0;
        String valuationMethod = useRevenueMultiple ? "EV_REVENUE" : "EV_EBITDA";
        BigDecimal anchorMultiple = anchored && enterpriseValue.signum() > 0
                ? enterpriseValue.divide(
                        useRevenueMultiple ? revenue : profit,
                        8,
                        RoundingMode.HALF_EVEN
                )
                : useRevenueMultiple ? new BigDecimal("8") : new BigDecimal("20");
        anchorMultiple = clamp(anchorMultiple, new BigDecimal("0.25"), new BigDecimal("100"));
        BigDecimal bullMultiple = clamp(
                anchorMultiple.multiply(new BigDecimal("1.15")),
                new BigDecimal("0.25"),
                new BigDecimal("100")
        ).setScale(8, RoundingMode.HALF_EVEN);
        BigDecimal bearMultiple = clamp(
                anchorMultiple.multiply(new BigDecimal("0.65")),
                new BigDecimal("0.25"),
                new BigDecimal("100")
        ).setScale(8, RoundingMode.HALF_EVEN);
        return List.of(
                new ScenarioAssumption(
                        "BULL", new BigDecimal("0.20"), bullMargin,
                        bullMultiple, new BigDecimal("0.25"),
                        valuationMethod, bullMultiple
                ),
                new ScenarioAssumption(
                        "BASE", new BigDecimal("0.08"), baseMargin,
                        anchorMultiple, new BigDecimal("0.50"),
                        valuationMethod, anchorMultiple
                ),
                new ScenarioAssumption(
                        "BEAR", new BigDecimal("-0.10"), bearMargin,
                        bearMultiple, new BigDecimal("0.25"),
                        valuationMethod, bearMultiple
                )
        );
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal minimum, BigDecimal maximum) {
        return value.max(minimum).min(maximum);
    }
}
