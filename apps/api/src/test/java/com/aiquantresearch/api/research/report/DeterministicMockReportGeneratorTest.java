package com.aiquantresearch.api.research.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiquantresearch.api.research.orchestration.ResearchExecutionContext;
import com.aiquantresearch.api.research.orchestration.StoredQuantResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeterministicMockReportGeneratorTest {

    private final ReportTestFixture fixture = new ReportTestFixture();
    private final DeterministicMockReportGenerator generator =
            new DeterministicMockReportGenerator(fixture.objectMapper());

    @Test
    void generatesCanonicalEvidenceBackedReportDeterministically() {
        JsonNode first = generator.generate(
                fixture.context(), fixture.sources(), fixture.quantResults(), fixture.evidence(), 2
        );
        JsonNode second = generator.generate(
                fixture.context(), fixture.sources(), fixture.quantResults(), fixture.evidence(), 2
        );

        assertThat(first).isEqualTo(second);
        assertThat(first.path("schemaVersion").asText()).isEqualTo("research_report_v1");
        assertThat(first.path("sections")).hasSizeGreaterThanOrEqualTo(4);
        assertThat(first.path("scenarioAnalysis").path("scenarios"))
                .extracting(item -> item.path("name").asText())
                .containsExactly("BULL", "BASE", "BEAR");
        assertThat(first.path("scenarioAnalysis").path("weightedImpliedPrice").asText())
                .isEqualTo("87.5");

        List<JsonNode> claims = claims(first);
        assertThat(claims).isNotEmpty();
        assertThat(claims).allSatisfy(claim -> {
            assertThat(claim.path("id").asText()).startsWith("cl_8d2a444aae_v2_");
            assertThat(claim.path("evidenceIds")).isNotEmpty();
            if ("CALCULATION".equals(claim.path("claimType").asText())) {
                assertThat(claim.path("calculationIds")).isNotEmpty();
                assertThat(claim.path("numericReferences")).isNotEmpty();
            }
        });

        ReportValidationResult validation = new ReportValidator().validate(
                first, fixture.quantResults(), fixture.evidence(), fixture.context()
        );
        assertThat(validation.valid()).as(validation.warnings().toString()).isTrue();
        assertThat(validation.partial()).isFalse();
        assertThat(validation.warnings()).isEmpty();
    }

    @Test
    void omitsUnrequestedFundamentalNarrativeWithoutFabricatingReferences() {
        ResearchExecutionContext context = fixture.context("en-US", false);
        var evidenceWithoutFundamentalNarrative = fixture.evidence().stream()
                .filter(item -> !"ev_fundamental_snapshot".equals(item.publicId()))
                .toList();

        JsonNode report = generator.generate(
                context,
                fixture.sources(),
                fixture.quantResults(),
                evidenceWithoutFundamentalNarrative,
                3
        );

        assertThat(report.path("sections"))
                .extracting(section -> section.path("id").asText())
                .containsExactly("overview", "performance", "risk", "scenario")
                .doesNotContain("fundamentals");
        assertThat(report.path("dataQuality").path("missingData"))
                .extracting(JsonNode::asText)
                .doesNotContain("fundamental_analysis: NOT_AVAILABLE (not requested)");
        assertThat(claims(report)).allSatisfy(claim ->
                assertThat(claim.path("evidenceIds"))
                        .extracting(JsonNode::asText)
                        .doesNotContain("ev_fundamental_snapshot"));

        ReportValidationResult validation = new ReportValidator().validate(
                report,
                fixture.quantResults(),
                evidenceWithoutFundamentalNarrative,
                context
        );
        assertThat(validation.valid()).as(validation.warnings().toString()).isTrue();
        assertThat(validation.partial()).isFalse();
        assertThat(validation.warnings()).isEmpty();
    }

    @Test
    void disclosesOptionalUnavailableMetricsWithoutDeclaringTheReportPartial() {
        List<StoredQuantResult> quantResults = new ArrayList<>(fixture.quantResults());
        ObjectNode result = fixture.objectMapper().createObjectNode();
        result.put("name", "forward_price_to_earnings");
        result.put("status", "NOT_AVAILABLE");
        result.putNull("value");
        result.put("unit", "RATIO");
        quantResults.add(new StoredQuantResult(
                UUID.fromString("00000000-0000-4000-8000-000000000004"),
                "calc_forward_price_to_earnings",
                "forward_price_to_earnings",
                null,
                "RATIO",
                "NOT_AVAILABLE",
                result
        ));

        JsonNode report = generator.generate(
                fixture.context(), fixture.sources(), quantResults, fixture.evidence(), 4
        );

        assertThat(report.path("dataQuality").path("missingData")).isEmpty();
        assertThat(report.path("dataQuality").path("limitations"))
                .extracting(JsonNode::asText)
                .contains("Optional quant metric unavailable: forward_price_to_earnings");
        ReportValidationResult validation = new ReportValidator().validate(
                report, quantResults, fixture.evidence(), fixture.context()
        );
        assertThat(validation.valid()).as(validation.warnings().toString()).isTrue();
        assertThat(validation.partial()).isFalse();
    }

    @Test
    void failsClosedForEtfWithoutAvailableScenarioCalculations() {
        ResearchExecutionContext base = fixture.context("en-US", false);
        ResearchExecutionContext etf = new ResearchExecutionContext(
                base.researchId(),
                base.ownerId(),
                "SPY",
                "ETF",
                base.locale(),
                base.dataMode(),
                base.request()
        );
        var quantWithoutScenarios = fixture.quantResults().stream()
                .filter(item -> !item.metricName().startsWith("scenario_"))
                .filter(item -> !"weighted_scenario_value".equals(item.metricName()))
                .toList();
        var evidenceWithoutScenarios = fixture.evidence().stream()
                .filter(item -> item.quantResultId() == null
                        || quantWithoutScenarios.stream().anyMatch(
                                quant -> quant.id().equals(item.quantResultId())
                        ))
                .toList();

        assertThatThrownBy(() -> generator.generate(
                etf,
                fixture.sources(),
                quantWithoutScenarios,
                evidenceWithoutScenarios,
                1
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Required calculation is unavailable: scenario_bull_implied_price");
    }

    private static List<JsonNode> claims(JsonNode root) {
        List<JsonNode> claims = new ArrayList<>();
        collect(root, claims);
        return claims;
    }

    private static void collect(JsonNode node, List<JsonNode> claims) {
        if (node.isObject()
                && node.has("statement")
                && node.has("claimType")
                && node.has("evidenceIds")) {
            claims.add(node);
            return;
        }
        node.forEach(child -> collect(child, claims));
    }
}
