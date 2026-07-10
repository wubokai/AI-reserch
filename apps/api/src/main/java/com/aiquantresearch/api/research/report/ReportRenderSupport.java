package com.aiquantresearch.api.research.report;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

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
