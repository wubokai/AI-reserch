package com.aiquantresearch.api.research.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Phase4QuantCompatibilityTest {

    private static final Set<String> REQUIRED = Set.of(
            "total_return",
            "cagr",
            "annualized_volatility",
            "max_drawdown",
            "sharpe_ratio",
            "scenario_bull_implied_price",
            "scenario_base_implied_price",
            "scenario_bear_implied_price",
            "weighted_scenario_value"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void optionalNotAvailableMetricsDoNotMakeTheResearchPartial() {
        ObjectNode response = responseWithRequiredMetrics();
        metric(response.withArray("metrics"), "forward_price_to_earnings", "NOT_AVAILABLE");

        assertThat(Phase3StepExecutor.hasUnavailableRequiredReportMetric(response)).isFalse();
    }

    @Test
    void missingOrUnavailableRequiredMetricMakesTheResearchPartial() {
        ObjectNode missing = responseWithRequiredMetrics();
        ArrayNode metrics = missing.withArray("metrics");
        for (int index = 0; index < metrics.size(); index++) {
            if ("cagr".equals(metrics.get(index).path("name").asText())) {
                metrics.remove(index);
                break;
            }
        }
        assertThat(Phase3StepExecutor.hasUnavailableRequiredReportMetric(missing)).isTrue();

        ObjectNode unavailable = responseWithRequiredMetrics();
        unavailable.withArray("metrics").forEach(item -> {
            if ("sharpe_ratio".equals(item.path("name").asText())) {
                ((ObjectNode) item).put("status", "NOT_AVAILABLE");
            }
        });
        assertThat(Phase3StepExecutor.hasUnavailableRequiredReportMetric(unavailable)).isTrue();
    }

    private ObjectNode responseWithRequiredMetrics() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode metrics = response.putArray("metrics");
        REQUIRED.forEach(name -> metric(metrics, name, "AVAILABLE"));
        return response;
    }

    private static void metric(ArrayNode metrics, String name, String status) {
        ObjectNode metric = metrics.addObject();
        metric.put("name", name);
        metric.put("status", status);
    }
}
