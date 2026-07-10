package com.aiquantresearch.api.research.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class EvidenceBuilder {

    private final ObjectMapper objectMapper;

    public EvidenceBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<EvidenceDraft> build(
            List<StoredSource> sources,
            List<StoredQuantResult> quantResults
    ) {
        List<EvidenceDraft> drafts = new ArrayList<>();
        for (StoredQuantResult quant : quantResults) {
            if (!"AVAILABLE".equals(quant.status())) {
                continue;
            }
            drafts.add(new EvidenceDraft(
                    "quant_" + quant.publicId(),
                    "QUANT_RESULT",
                    humanize(quant.metricName()),
                    "Deterministic quant_v1 result for " + quant.metricName() + ".",
                    quant.result(),
                    quant.unit(),
                    null,
                    quant.id(),
                    0.98
            ));
        }
        for (StoredSource source : sources) {
            drafts.add(sourceDraft(source));
        }
        return List.copyOf(drafts);
    }

    private EvidenceDraft sourceDraft(StoredSource source) {
        String type;
        String title;
        String summary;
        ObjectNode value = objectMapper.createObjectNode();
        value.put("sourceSnapshotId", source.id().toString());
        value.put("contentHash", source.contentHash());
        value.put("purpose", source.purpose());

        switch (source.purpose()) {
            case "MARKET_DATA", "BENCHMARK_DATA" -> {
                type = "MARKET_PRICE";
                String symbol = source.payload().path("symbol").asText(source.externalSourceId());
                var prices = source.payload().path("prices");
                var last = prices.isArray() && !prices.isEmpty()
                        ? prices.get(prices.size() - 1)
                        : objectMapper.createObjectNode();
                title = symbol + " synthetic adjusted market series";
                summary = "Fixed five-year synthetic adjusted OHLCV series ending "
                        + source.payload().path("periodEnd").asText("unknown") + ".";
                value.put("symbol", symbol);
                value.put("asOfDate", source.payload().path("periodEnd").asText());
                value.put(
                        "latestAdjustedClose",
                        last.path("adjustedClose").asText(last.path("close").asText())
                );
                value.put("sampleSize", prices.size());
            }
            case "FUNDAMENTALS" -> {
                type = "FINANCIAL_METRIC";
                title = source.externalSourceId() + " synthetic fundamentals";
                summary = "Fixed synthetic normalized fundamentals for deterministic analysis.";
                value.set("metrics", source.payload().path("metrics"));
                value.put("asOfDate", source.payload().path("asOfDate").asText());
            }
            case "FILING" -> {
                type = "SEC_FILING";
                title = source.externalSourceId() + " synthetic filings";
                summary = "Two fixed synthetic filing summaries used only for demo Evidence.";
                value.set("filings", source.payload().path("filings"));
                value.put("asOfDate", source.payload().path("asOfDate").asText());
            }
            case "MACRO" -> {
                type = "MACRO_OBSERVATION";
                title = "Synthetic macro series";
                summary = "Fixed synthetic policy-rate and inflation observations.";
                value.set("series", source.payload().path("series"));
                value.put("asOfDate", source.payload().path("asOfDate").asText());
            }
            default -> {
                type = "COMPANY_PROFILE";
                title = source.externalSourceId() + " synthetic security profile";
                summary = "Local deterministic security-master resolution.";
                value.set("profile", source.payload());
            }
        }
        return new EvidenceDraft(
                "source_" + source.id(),
                type,
                title,
                summary,
                value,
                null,
                source.id(),
                null,
                0.95
        );
    }

    private static String humanize(String value) {
        String normalized = value.replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }
}
