package com.aiquantresearch.api.research.report;

import com.aiquantresearch.api.research.orchestration.StoredEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

@Component
public class ReportHtmlRenderer {

    public String render(JsonNode report, List<StoredEvidence> evidence) {
        if (report == null || !report.isObject()) {
            throw new IllegalArgumentException("A report object is required");
        }
        List<StoredEvidence> orderedEvidence = evidence.stream()
                .sorted(Comparator.comparing(StoredEvidence::publicId))
                .toList();
        StringBuilder html = new StringBuilder(16_384);
        html.append("<!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"")
                .append(escape(report.path("locale").asText("en-US")))
                .append("\"><head><meta charset=\"UTF-8\" /><title>")
                .append(escape(report.path("title").asText("Research report")))
                .append("</title><style>")
                .append(styles())
                .append("</style></head><body>");
        html.append("<header><div class=\"watermark\">")
                .append(escape(DeterministicMockReportGenerator.DEMO_WATERMARK))
                .append("</div><h1>")
                .append(escape(report.path("title").asText("Research report")))
                .append("</h1><div class=\"metadata\">")
                .append(meta("Symbol", report.path("symbol").asText()))
                .append(meta("Security type", report.path("securityType").asText()))
                .append(meta("As of", report.path("asOfDate").asText()))
                .append(meta("Data mode", report.path("dataMode").asText()))
                .append("</div></header><main>");

        for (JsonNode section : report.path("sections")) {
            html.append("<section><h2>")
                    .append(escape(section.path("heading").asText()))
                    .append("</h2>");
            appendClaims(html, section.path("claims"));
            String transition = section.path("transitionText").asText();
            if (!transition.isBlank()) {
                html.append("<p class=\"transition\">").append(escape(transition)).append("</p>");
            }
            html.append("</section>");
        }

        html.append("<section><h2>Scenario analysis</h2><table><thead><tr>")
                .append("<th>Scenario</th><th>Probability</th><th>Revenue growth</th>")
                .append("<th>EBITDA margin</th><th>EV/EBITDA</th>")
                .append("<th>Equity value</th><th>Implied price</th><th>Upside/downside</th>")
                .append("</tr></thead><tbody>");
        for (JsonNode scenario : report.path("scenarioAnalysis").path("scenarios")) {
            html.append("<tr>")
                    .append(cell(scenario.path("name")))
                    .append(cell(scenario.path("probability")))
                    .append(cell(scenario.path("revenueGrowth")))
                    .append(cell(scenario.path("targetEbitdaMargin")))
                    .append(cell(scenario.path("evToEbitdaMultiple")))
                    .append(cell(scenario.path("impliedEquityValue")))
                    .append(cell(scenario.path("impliedPrice")))
                    .append(cell(scenario.path("upsideDownside")))
                    .append("</tr>");
        }
        html.append("</tbody></table><p class=\"weighted\">Weighted implied price: ")
                .append(escape(report.path("scenarioAnalysis").path("weightedImpliedPrice").asText()))
                .append("</p>");
        appendClaims(html, report.path("scenarioAnalysis").path("summaryClaims"));
        html.append("</section>");

        appendNamedClaims(html, "Bull case", report.path("bullCase"));
        appendNamedClaims(html, "Bear case", report.path("bearCase"));
        appendNamedClaims(html, "Catalysts", report.path("catalysts"));
        html.append("<section><h2>Risks</h2>");
        for (JsonNode risk : report.path("risks")) {
            html.append("<h3>").append(escape(risk.path("category").asText())).append("</h3>");
            appendClaim(html, risk.path("claim"));
        }
        html.append("</section>");
        appendNamedClaims(html, "Conclusion", report.path("conclusion"));

        html.append("<section class=\"sources\"><h2>Sources</h2><ol>");
        for (StoredEvidence item : orderedEvidence) {
            html.append("<li><span class=\"source-id\">")
                    .append(escape(item.publicId()))
                    .append("</span><strong>")
                    .append(escape(item.title()))
                    .append("</strong><p>")
                    .append(escape(item.summary()))
                    .append("</p></li>");
        }
        html.append("</ol></section></main><footer><h2>Disclaimer</h2><p>")
                .append(disclaimerHtml(report.path("disclaimer").asText(
                        DeterministicMockReportGenerator.DISCLAIMER
                )))
                .append("</p><div class=\"footer-watermark\">")
                .append(escape(DeterministicMockReportGenerator.DEMO_WATERMARK))
                .append("</div></footer></body></html>");
        return html.toString();
    }

    private static void appendNamedClaims(StringBuilder html, String heading, JsonNode claims) {
        html.append("<section><h2>").append(escape(heading)).append("</h2>");
        appendClaims(html, claims);
        html.append("</section>");
    }

    private static void appendClaims(StringBuilder html, JsonNode claims) {
        if (!claims.isArray() || claims.isEmpty()) {
            html.append("<p class=\"empty\">No claims.</p>");
            return;
        }
        html.append("<ul class=\"claims\">");
        claims.forEach(claim -> appendClaim(html, claim));
        html.append("</ul>");
    }

    private static void appendClaim(StringBuilder html, JsonNode claim) {
        html.append("<li class=\"claim\"><p>")
                .append(escape(claim.path("statement").asText()))
                .append("</p><div class=\"trace\">Evidence: ")
                .append(escape(join(claim.path("evidenceIds"))));
        if (claim.path("calculationIds").isArray() && !claim.path("calculationIds").isEmpty()) {
            html.append("; calculations: ")
                    .append(escape(join(claim.path("calculationIds"))));
        }
        html.append("</div></li>");
    }

    private static String meta(String label, String value) {
        return "<span><b>" + escape(label) + ":</b> " + escape(value) + "</span>";
    }

    private static String cell(JsonNode value) {
        return "<td>" + escape(value.asText()) + "</td>";
    }

    private static String join(JsonNode values) {
        StringJoiner joiner = new StringJoiner(", ");
        if (values.isArray()) {
            values.forEach(value -> joiner.add(value.asText()));
        }
        return joiner.toString();
    }

    private static String escape(String value) {
        return ReportRenderSupport.html(value);
    }

    private static String disclaimerHtml(String disclaimer) {
        int localizedStart = disclaimer.indexOf(" 本报告");
        if (localizedStart < 0) {
            return escape(disclaimer);
        }
        return escape(disclaimer.substring(0, localizedStart))
                + "<br />"
                + escape(disclaimer.substring(localizedStart + 1));
    }

    private static String styles() {
        return """
                @page { size: A4; margin: 18mm 15mm 20mm; }
                * { box-sizing: border-box; }
                body { color: #172033; font-family: 'Report CJK', 'PingFang SC',
                       'Heiti SC', sans-serif; font-size: 10pt; line-height: 1.48; margin: 0; }
                header { border-bottom: 2px solid #244b76; padding-bottom: 12px; }
                h1 { color: #132f4f; font-size: 22pt; line-height: 1.15; margin: 10px 0 8px; }
                h2 { color: #173e69; font-size: 15pt; margin: 18px 0 8px;
                    page-break-after: avoid; }
                h3 { color: #355f89; font-size: 11pt; margin: 12px 0 4px; }
                p { margin: 4px 0 8px; }
                section { page-break-inside: auto; }
                .watermark, .footer-watermark { color: #9b2c2c; font-size: 9pt;
                    font-weight: bold; letter-spacing: .05em; text-transform: uppercase; }
                .metadata { display: block; color: #44546a; }
                .metadata span { display: inline-block; margin-right: 18px; }
                .claims { margin: 5px 0 8px 18px; padding: 0; }
                .claim { margin: 0 0 8px; page-break-inside: avoid; }
                .claim p { margin-bottom: 2px; }
                .trace { color: #65758b; font-size: 8pt; word-wrap: break-word; }
                .transition { color: #526477; font-style: italic; border-left: 3px solid #a9bed4;
                    padding: 5px 9px; }
                table { border-collapse: collapse; font-size: 7.5pt; margin: 8px 0; width: 100%; }
                th { background: #e8eff7; color: #173e69; font-weight: bold; }
                th, td { border: 1px solid #b9c6d4; padding: 4px; text-align: right; }
                th:first-child, td:first-child { text-align: left; }
                .weighted { background: #edf4ea; border-left: 4px solid #538347;
                    font-weight: bold; padding: 7px 9px; }
                .sources { font-size: 9pt; line-height: 1.3; margin-top: 16px; }
                .sources ol { padding-left: 20px; }
                .sources li { margin-bottom: 4px; page-break-inside: avoid; }
                .sources p { color: #526477; margin: 0; }
                .source-id { color: #65758b; display: block; font-family: monospace; font-size: 7pt; }
                footer { border-top: 2px solid #244b76; margin-top: 20px; padding-top: 8px; }
                footer h2 { margin-top: 0; }
                .empty { color: #65758b; font-style: italic; }
                """;
    }
}
