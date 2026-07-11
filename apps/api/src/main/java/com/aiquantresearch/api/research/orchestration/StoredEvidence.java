package com.aiquantresearch.api.research.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;

public record StoredEvidence(
        UUID id,
        String publicId,
        String evidenceType,
        String title,
        String summary,
        JsonNode value,
        String unit,
        UUID sourceSnapshotId,
        UUID quantResultId,
        BigDecimal qualityScore,
        boolean primarySource,
        String freshnessStatus,
        LocalDate effectiveDate,
        boolean demoData,
        String sourceName,
        String sourceUrl,
        String sourceType,
        String attribution,
        String licensePolicyVersion
) {

    public StoredEvidence(
            UUID id,
            String publicId,
            String evidenceType,
            String title,
            String summary,
            JsonNode value,
            String unit,
            UUID sourceSnapshotId,
            UUID quantResultId,
            BigDecimal qualityScore,
            boolean primarySource,
            String freshnessStatus,
            LocalDate effectiveDate,
            boolean demoData
    ) {
        this(id, publicId, evidenceType, title, summary, value, unit,
                sourceSnapshotId, quantResultId, qualityScore, primarySource,
                freshnessStatus, effectiveDate, demoData,
                sourceSnapshotId == null ? "Internal Analytics" : "Source snapshot",
                null,
                sourceSnapshotId == null ? "INTERNAL_CALCULATION" : "OTHER",
                null,
                null);
    }

    public StoredEvidence(
            UUID id,
            String publicId,
            String evidenceType,
            String title,
            String summary,
            JsonNode value,
            String unit,
            UUID sourceSnapshotId,
            UUID quantResultId
    ) {
        this(id, publicId, evidenceType, title, summary, value, unit,
                sourceSnapshotId, quantResultId, new BigDecimal("0.9500"),
                true, "FRESH", effectiveDate(value), true);
    }

    private static LocalDate effectiveDate(JsonNode value) {
        String text = value == null ? "" : value.path("asOfDate").asText();
        try {
            return text.isBlank() ? null : LocalDate.parse(text);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
