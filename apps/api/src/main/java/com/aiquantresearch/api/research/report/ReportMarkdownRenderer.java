package com.aiquantresearch.api.research.report;

import com.aiquantresearch.api.research.orchestration.StoredEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

@Component
public class ReportMarkdownRenderer {

    public String render(JsonNode report, List<StoredEvidence> evidence) {
        if (report == null || !report.isObject()) {
            throw new IllegalArgumentException("A report object is required");
        }
        List<StoredEvidence> orderedEvidence = evidence.stream()
                .sorted(Comparator.comparing(StoredEvidence::publicId))
                .toList();
        List<ReportSourceAttribution> attributions = ReportRenderSupport.attributions(evidence);
        StringBuilder output = new StringBuilder();
        output.append("# ").append(md(report.path("title").asText("Research report"))).append("\n\n");
        if (ReportRenderSupport.isDemo(report)) {
            output.append("> **").append(md(DeterministicMockReportGenerator.DEMO_WATERMARK))
                    .append("**\n\n");
        }
        output.append("- **Symbol:** ").append(md(report.path("symbol").asText())).append('\n');
        output.append("- **Security type:** ").append(md(report.path("securityType").asText())).append('\n');
        output.append("- **As of:** ").append(md(report.path("asOfDate").asText())).append('\n');
        output.append("- **Data mode:** ").append(md(report.path("dataMode").asText())).append("\n\n");

        for (JsonNode section : report.path("sections")) {
            output.append("## ").append(md(section.path("heading").asText())).append("\n\n");
            appendClaims(output, section.path("claims"));
            String transition = section.path("transitionText").asText();
            if (!transition.isBlank()) {
                output.append('_').append(md(transition)).append("_\n\n");
            }
        }

        output.append("## Scenario analysis\n\n");
        output.append("**Current market price:** ")
                .append(md(report.path("scenarioAnalysis").path("currentPrice").asText()))
                .append("\n\n");
        output.append("| Scenario | Probability | Revenue growth | EBITDA margin | Valuation method | Valuation multiple | Implied equity value | Implied price | Upside/downside |\n");
        output.append("|---|---:|---:|---:|---|---:|---:|---:|---:|\n");
        for (JsonNode scenario : report.path("scenarioAnalysis").path("scenarios")) {
            output.append("| ").append(md(scenario.path("name").asText()))
                    .append(" | ").append(md(scenario.path("probability").asText()))
                    .append(" | ").append(md(scenario.path("revenueGrowth").asText()))
                    .append(" | ").append(md(scenario.path("targetEbitdaMargin").asText()))
                    .append(" | ").append(md(scenario.path("valuationMethod").asText()))
                    .append(" | ").append(md(scenario.path("valuationMultiple").asText()))
                    .append(" | ").append(md(scenario.path("impliedEquityValue").asText()))
                    .append(" | ").append(md(scenario.path("impliedPrice").asText()))
                    .append(" | ").append(md(scenario.path("upsideDownside").asText()))
                    .append(" |\n");
        }
        output.append("\n**Weighted implied price:** ")
                .append(md(report.path("scenarioAnalysis").path("weightedImpliedPrice").asText()))
                .append("\n\n");
        appendClaims(output, report.path("scenarioAnalysis").path("summaryClaims"));

        appendNamedClaims(output, "Bull case", report.path("bullCase"));
        appendNamedClaims(output, "Bear case", report.path("bearCase"));
        appendNamedClaims(output, "Catalysts", report.path("catalysts"));
        output.append("## Risks\n\n");
        for (JsonNode risk : report.path("risks")) {
            output.append("### ").append(md(risk.path("category").asText())).append("\n\n");
            appendClaim(output, risk.path("claim"));
        }
        appendNamedClaims(output, "Conclusion", report.path("conclusion"));

        if (!attributions.isEmpty()) {
            output.append("## Data source attribution\n\n");
            for (ReportSourceAttribution attribution : attributions) {
                output.append("- **").append(md(attribution.sourceName())).append("**")
                        .append(" (`").append(md(attribution.sourceType())).append("`): ")
                        .append(md(attribution.statement()));
                if (!attribution.sourceUrl().isBlank()) {
                    output.append(" Source URL: ").append(md(attribution.sourceUrl())).append('.');
                }
                if (!attribution.licensePolicyVersion().isBlank()) {
                    output.append(" Policy: `")
                            .append(md(attribution.licensePolicyVersion())).append("`.");
                }
                output.append('\n');
            }
            output.append('\n');
        }

        output.append("## Sources\n\n");
        for (StoredEvidence item : orderedEvidence) {
            output.append("- **").append(md(item.publicId())).append(" — ")
                    .append(md(item.title())).append("**: ")
                    .append(md(item.summary()))
                    .append(" Source: ").append(md(item.sourceName()))
                    .append(" (`").append(md(item.sourceType())).append("`).\n");
        }
        output.append("\n## Disclaimer\n\n");
        output.append(md(report.path("disclaimer").asText(
                DeterministicMockReportGenerator.DISCLAIMER
        ))).append('\n');
        return output.toString();
    }

    private static void appendNamedClaims(StringBuilder output, String heading, JsonNode claims) {
        output.append("## ").append(md(heading)).append("\n\n");
        appendClaims(output, claims);
    }

    private static void appendClaims(StringBuilder output, JsonNode claims) {
        if (!claims.isArray() || claims.isEmpty()) {
            output.append("_No claims._\n\n");
            return;
        }
        claims.forEach(claim -> appendClaim(output, claim));
    }

    private static void appendClaim(StringBuilder output, JsonNode claim) {
        output.append("- ").append(md(claim.path("statement").asText())).append("  \n");
        output.append("  Evidence: ").append(md(join(claim.path("evidenceIds"))));
        if (claim.path("calculationIds").isArray() && !claim.path("calculationIds").isEmpty()) {
            output.append("; calculations: ").append(md(join(claim.path("calculationIds"))));
        }
        output.append("\n\n");
    }

    private static String join(JsonNode values) {
        StringJoiner joiner = new StringJoiner(", ");
        if (values.isArray()) {
            values.forEach(value -> joiner.add(value.asText()));
        }
        return joiner.toString();
    }

    private static String md(String value) {
        return ReportRenderSupport.markdown(value);
    }
}
