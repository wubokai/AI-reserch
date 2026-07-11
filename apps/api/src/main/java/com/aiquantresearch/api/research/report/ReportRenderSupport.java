package com.aiquantresearch.api.research.report;

import com.aiquantresearch.api.research.orchestration.StoredEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ReportRenderSupport {

    private ReportRenderSupport() {
    }

    static String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }

    static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(item -> values.add(item.asText("")));
        }
        return List.copyOf(values);
    }

    static boolean isDemo(JsonNode report) {
        return !"REAL".equals(report.path("dataMode").asText());
    }

    static List<ReportSourceAttribution> attributions(List<StoredEvidence> evidence) {
        Map<String, ReportSourceAttribution> unique = new LinkedHashMap<>();
        evidence.stream()
                .filter(item -> item.sourceSnapshotId() != null)
                .filter(item -> !value(item.attribution()).isBlank()
                        || !value(item.licensePolicyVersion()).isBlank())
                .sorted(Comparator.comparing((StoredEvidence item) -> value(item.sourceName()))
                        .thenComparing(item -> value(item.sourceUrl())))
                .forEach(item -> {
                    String statement = value(item.attribution());
                    if (statement.isBlank()) {
                        statement = defaultAttribution(item.sourceName(), item.sourceType());
                    }
                    ReportSourceAttribution attribution = new ReportSourceAttribution(
                            value(item.sourceName()),
                            value(item.sourceUrl()),
                            value(item.sourceType()),
                            statement,
                            value(item.licensePolicyVersion())
                    );
                    String key = String.join("\u0000",
                            attribution.sourceName(),
                            attribution.sourceUrl(),
                            attribution.statement(),
                            attribution.licensePolicyVersion());
                    unique.putIfAbsent(key, attribution);
                });
        return List.copyOf(unique.values());
    }

    private static String defaultAttribution(String sourceName, String sourceType) {
        String name = value(sourceName);
        if (name.startsWith("SEC_EDGAR") || "SEC_FILING".equals(sourceType)) {
            return "Data sourced from the U.S. Securities and Exchange Commission (SEC) EDGAR system.";
        }
        return name.isBlank() ? "Source attribution unavailable." : "Data sourced from " + name + ".";
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    static String html(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    static String markdown(String value) {
        if (value == null) {
            return "";
        }
        String safe = value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        StringBuilder escaped = new StringBuilder(safe.length() + 16);
        for (int index = 0; index < safe.length(); index++) {
            char character = safe.charAt(index);
            if ("\\`*_{}[]()#+-.!|".indexOf(character) >= 0) {
                escaped.append('\\');
            }
            escaped.append(character);
        }
        return escaped.toString();
    }
}
