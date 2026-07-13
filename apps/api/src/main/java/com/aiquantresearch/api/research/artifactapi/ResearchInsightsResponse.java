package com.aiquantresearch.api.research.artifactapi;

import com.aiquantresearch.api.shared.domain.DataMode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ResearchInsightsResponse(
        UUID researchId,
        int reportVersion,
        DataMode dataMode,
        PriceChart priceChart,
        ValuationInsights valuation,
        PeerComparison peers
) {

    public record PriceChart(
            String symbol,
            String currency,
            @JsonInclude(JsonInclude.Include.ALWAYS) String provider,
            @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate asOfDate,
            @JsonInclude(JsonInclude.Include.ALWAYS) Instant retrievedAt,
            String methodology,
            List<PricePoint> points,
            List<PriceRangeStats> rangeStats,
            TechnicalSummary technicalSummary
    ) {
    }

    public record PriceRangeStats(
            String range,
            LocalDate periodStart,
            LocalDate periodEnd,
            BigDecimal firstPrice,
            BigDecimal lastPrice,
            BigDecimal periodReturn,
            BigDecimal high,
            BigDecimal low,
            BigDecimal averageVolume
    ) {
    }

    public record PricePoint(
            LocalDate date,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal adjustedClose,
            long volume,
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal ma20,
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal ma50
    ) {
    }

    public record TechnicalSummary(
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal currentPrice,
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal priceVsMa20,
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal priceVsMa50,
            String signal
    ) {
    }

    public record ValuationInsights(
            boolean available,
            @JsonInclude(JsonInclude.Include.ALWAYS) String unavailableReason,
            String currency,
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal currentPrice,
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal weightedImpliedPrice,
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal premiumDiscountToWeightedValue,
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal marketImpliedRevenueGrowth,
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal marketImpliedGrowthGap,
            @JsonInclude(JsonInclude.Include.ALWAYS) String valuationMethod,
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal baseRevenueGrowth,
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal baseEbitdaMargin,
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal baseValuationMultiple,
            String formula,
            List<String> caveats,
            @JsonInclude(JsonInclude.Include.ALWAYS) SensitivityMatrix sensitivity
    ) {
    }

    public record SensitivityMatrix(
            List<BigDecimal> revenueGrowthRates,
            List<BigDecimal> valuationMultiples,
            List<SensitivityRow> rows
    ) {
    }

    public record SensitivityRow(
            BigDecimal revenueGrowthRate,
            List<BigDecimal> impliedPrices,
            List<BigDecimal> upsideDownside
    ) {
    }

    public record PeerComparison(
            boolean available,
            @JsonInclude(JsonInclude.Include.ALWAYS) String groupKey,
            @JsonInclude(JsonInclude.Include.ALWAYS) String groupLabel,
            String methodology,
            int availableCount,
            int configuredCount,
            String coverageMessage,
            List<PeerRow> rows
    ) {
    }

    public record PeerRow(
            String symbol,
            UUID researchId,
            int reportVersion,
            boolean target,
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal currentPrice,
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal weightedImpliedPrice,
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal baseCaseUpside,
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal revenueCagr,
            @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal operatingMargin,
            double dataQuality,
            LocalDate asOfDate
    ) {
    }
}
