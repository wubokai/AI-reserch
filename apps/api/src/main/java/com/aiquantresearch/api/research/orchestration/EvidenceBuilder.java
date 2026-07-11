package com.aiquantresearch.api.research.orchestration;

import com.aiquantresearch.api.research.report.EvidenceScoringPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
                    EvidenceScoringPolicy.evidenceQuality(
                            true, true, "FRESH", true, true
                    ).doubleValue()
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
                title = source.externalSourceId() + (source.demoData()
                        ? " synthetic fundamentals"
                        : " SEC XBRL fundamentals");
                summary = source.demoData()
                        ? "Fixed synthetic normalized fundamentals for deterministic analysis."
                        : "Normalized financial facts with SEC taxonomy, concept, period and accession lineage.";
                value.set("metrics", source.payload().path("metrics"));
                value.put("asOfDate", source.payload().path("asOfDate").asText());
            }
            case "FILING" -> {
                type = "SEC_FILING";
                title = source.externalSourceId() + (source.demoData()
                        ? " synthetic filings"
                        : " SEC filings");
                summary = source.demoData()
                        ? "Two fixed synthetic filing summaries used only for demo Evidence."
                        : "Official SEC filing metadata and normalized document summaries.";
                ArrayNode filings = value.putArray("filings");
                source.payload().path("filings").forEach(document -> {
                    ObjectNode metadata = filings.addObject();
                    for (String field : List.of(
                            "documentId", "accessionNumber", "formType", "filingDate",
                            "reportPeriod", "title", "summary"
                    )) {
                        if (document.hasNonNull(field)) {
                            metadata.set(field, document.path(field));
                        }
                    }
                });
                value.put("asOfDate", source.payload().path("asOfDate").asText());
            }
            case "MACRO" -> {
                type = "MACRO_OBSERVATION";
                title = source.demoData() ? "Synthetic macro series" : "FRED macro series";
                summary = source.demoData()
                        ? "Fixed synthetic policy-rate and inflation observations."
                        : "FRED observations with frequency, vintage and revision boundaries.";
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
                EvidenceScoringPolicy.evidenceQuality(
                        source.primarySource(),
                        source.provider().startsWith("MOCK_"),
                        source.freshnessStatus(),
                        source.contentHash() != null && !source.contentHash().isBlank(),
                        true
                ).doubleValue()
        );
    }

    private static String humanize(String value) {
        String normalized = value.replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }
}
