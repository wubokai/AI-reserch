package com.aiquantresearch.api.research.artifactapi;

import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ArtifactApiResponses {

    private ArtifactApiResponses() {
    }

    public record PageMetadata(
            int number,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last
    ) {
        static PageMetadata of(int page, int size, long total) {
            int pages = total == 0 ? 0 : Math.toIntExact((total + size - 1) / size);
            return new PageMetadata(
                    page,
                    size,
                    total,
                    pages,
                    page == 0,
                    pages == 0 || page >= pages - 1
            );
        }
    }

    public record SecurityItem(
            UUID securityId,
            String symbol,
            String companyName,
            String exchange,
            String securityType,
            String currency,
            @JsonInclude(JsonInclude.Include.ALWAYS) String sector,
            @JsonInclude(JsonInclude.Include.ALWAYS) String industry,
            @JsonInclude(JsonInclude.Include.ALWAYS) String cik,
            boolean active,
            DataMode dataMode
    ) {
    }

    public record SecuritySearchResponse(List<SecurityItem> items, DataMode dataMode) {
    }

    public record RateLimitStatus(
            boolean limited,
            @JsonInclude(JsonInclude.Include.ALWAYS) Integer remaining,
            @JsonInclude(JsonInclude.Include.ALWAYS) Instant resetsAt
    ) {
        static RateLimitStatus unlimited() {
            return new RateLimitStatus(false, null, null);
        }
    }

    public record ProviderStatus(
            String name,
            List<String> capabilities,
            String mode,
            String status,
            boolean configured,
            @JsonInclude(JsonInclude.Include.ALWAYS) Instant lastCheckedAt,
            @JsonInclude(JsonInclude.Include.ALWAYS) Instant lastSuccessAt,
            @JsonInclude(JsonInclude.Include.ALWAYS) Long latencyMs,
            RateLimitStatus rateLimit,
            @JsonInclude(JsonInclude.Include.ALWAYS) String message
    ) {
    }

    public record ProviderStatusResponse(
            String status,
            DataMode dataMode,
            List<ProviderStatus> providers,
            Instant checkedAt
    ) {
    }

    public record EvidenceItem(
            String evidenceId,
            String evidenceType,
            String title,
            String summary,
            JsonNode value,
            @JsonInclude(JsonInclude.Include.ALWAYS) String unit,
            String sourceName,
            @JsonInclude(JsonInclude.Include.ALWAYS) String sourceUrl,
            String sourceType,
            @JsonInclude(JsonInclude.Include.ALWAYS) Instant publishedAt,
            Instant retrievedAt,
            @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate effectiveDate,
            boolean isPrimarySource,
            String freshnessStatus,
            double qualityScore,
            String rawDataHash,
            boolean isDemoData,
            List<String> relatedClaimIds
    ) {
    }

    public record EvidencePage(
            List<EvidenceItem> items,
            PageMetadata page,
            DataMode dataMode
    ) {
    }

    public record ReportVersionSummary(
            UUID researchId,
            int version,
            String title,
            String symbol,
            LocalDate asOfDate,
            String validationStatus,
            DataMode dataMode,
            String contentHash,
            Instant createdAt
    ) {
    }

    public record ReportVersionPage(
            List<ReportVersionSummary> items,
            PageMetadata page
    ) {
    }

    public record ReportVersionResponse(
            UUID researchId,
            int version,
            String validationStatus,
            String contentHash,
            Instant createdAt,
            Instant generatedAt,
            JsonNode report
    ) {
    }
}
