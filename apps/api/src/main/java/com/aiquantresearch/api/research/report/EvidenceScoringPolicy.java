package com.aiquantresearch.api.research.report;

import com.aiquantresearch.api.research.orchestration.ResearchExecutionContext;
import com.aiquantresearch.api.research.orchestration.StoredEvidence;
import com.aiquantresearch.api.research.orchestration.StoredQuantResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class EvidenceScoringPolicy {

    public static final String CONFIDENCE_VERSION = "confidence_v1";
    public static final String DATA_QUALITY_VERSION = "data_quality_v1";

    private EvidenceScoringPolicy() {
    }

    public static BigDecimal evidenceQuality(
            boolean primary,
            boolean registeredTrustedSecondary,
            String freshnessStatus,
            boolean hasHash,
            boolean exactLocator
    ) {
        BigDecimal sourceWeight = primary
                ? BigDecimal.ONE
                : registeredTrustedSecondary
                        ? new BigDecimal("0.75")
                        : new BigDecimal("0.50");
        BigDecimal traceWeight = hasHash && exactLocator
                ? BigDecimal.ONE
                : hasHash ? new BigDecimal("0.85") : new BigDecimal("0.60");
        return clamp(sourceWeight.multiply(freshnessWeight(freshnessStatus)).multiply(traceWeight))
                .setScale(4, RoundingMode.HALF_UP);
    }

    public static BigDecimal claimConfidence(
            String claimType,
            List<StoredEvidence> evidence,
            boolean unresolvedConflict
    ) {
        List<BigDecimal> top = evidence.stream()
                .map(StoredEvidence::qualityScore)
                .filter(value -> value != null)
                .sorted(Comparator.reverseOrder())
                .limit(3)
                .toList();
        if (top.isEmpty()) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal average = top.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(top.size()), 8, RoundingMode.HALF_UP);
        BigDecimal factor = switch (claimType) {
            case "FACT", "CALCULATION" -> BigDecimal.ONE;
            case "INFERENCE" -> new BigDecimal("0.85");
            case "OPINION" -> new BigDecimal("0.65");
            default -> BigDecimal.ZERO;
        };
        BigDecimal score = clamp(average.multiply(factor));
        if (unresolvedConflict) {
            score = score.min(new BigDecimal("0.60"));
        }
        return score.setScale(2, RoundingMode.HALF_UP);
    }

    public static DataQualityAssessment dataQuality(
            ResearchExecutionContext context,
            List<StoredEvidence> evidence,
            List<StoredQuantResult> calculations,
            List<String> sourceConflicts
    ) {
        Set<String> availableTypes = new LinkedHashSet<>();
        evidence.forEach(item -> availableTypes.add(item.evidenceType()));
        boolean quantAvailable = calculations.stream()
                .anyMatch(item -> "AVAILABLE".equals(item.status()) && item.value() != null);

        List<Requirement> requirements = new ArrayList<>();
        requirements.add(new Requirement("market_data", availableTypes.contains("MARKET_PRICE")));
        requirements.add(new Requirement("quant_results", quantAvailable));
        if (context.request().path("includeFundamentalAnalysis").asBoolean(true)) {
            requirements.add(new Requirement(
                    "fundamentals",
                    availableTypes.contains("FINANCIAL_METRIC")
            ));
        }
        requirements.add(new Requirement("filing", availableTypes.contains("SEC_FILING")));

        List<String> missing = requirements.stream()
                .filter(item -> !item.available())
                .map(item -> item.name() + ": NOT_AVAILABLE")
                .toList();
        BigDecimal requiredCoverage = ratio(
                requirements.stream().filter(Requirement::available).count(),
                requirements.size()
        );
        BigDecimal freshnessCoverage = evidence.isEmpty()
                ? BigDecimal.ZERO
                : evidence.stream()
                        .map(item -> freshnessWeight(item.freshnessStatus()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(evidence.size()), 8, RoundingMode.HALF_UP);
        BigDecimal primaryCoverage = evidence.isEmpty()
                ? BigDecimal.ZERO
                : ratio(evidence.stream().filter(StoredEvidence::primarySource).count(), evidence.size());
        BigDecimal consistency = sourceConflicts.isEmpty()
                ? BigDecimal.ONE
                : BigDecimal.ONE.subtract(
                        new BigDecimal("0.25").multiply(BigDecimal.valueOf(sourceConflicts.size()))
                ).max(BigDecimal.ZERO);
        BigDecimal score = requiredCoverage.multiply(new BigDecimal("0.40"))
                .add(freshnessCoverage.multiply(new BigDecimal("0.20")))
                .add(primaryCoverage.multiply(new BigDecimal("0.20")))
                .add(consistency.multiply(new BigDecimal("0.20")));
        if (!availableTypes.contains("MARKET_PRICE")) {
            score = score.min(new BigDecimal("0.45"));
        }
        if ("COMMON_STOCK".equals(context.securityType())
                && context.request().path("includeFundamentalAnalysis").asBoolean(true)
                && (!availableTypes.contains("FINANCIAL_METRIC")
                || !availableTypes.contains("SEC_FILING"))) {
            score = score.min(new BigDecimal("0.60"));
        }

        List<String> staleIds = evidence.stream()
                .filter(item -> !"FRESH".equals(item.freshnessStatus()))
                .map(StoredEvidence::publicId)
                .sorted()
                .toList();
        return new DataQualityAssessment(
                clamp(score).setScale(2, RoundingMode.HALF_UP),
                missing,
                staleIds,
                List.copyOf(sourceConflicts),
                requiredCoverage.setScale(2, RoundingMode.HALF_UP),
                freshnessCoverage.setScale(2, RoundingMode.HALF_UP),
                primaryCoverage.setScale(2, RoundingMode.HALF_UP),
                consistency.setScale(2, RoundingMode.HALF_UP)
        );
    }

    public static BigDecimal freshnessWeight(String status) {
        return switch (status == null ? "UNKNOWN" : status) {
            case "FRESH" -> BigDecimal.ONE;
            case "STALE" -> new BigDecimal("0.80");
            case "VERY_STALE" -> new BigDecimal("0.50");
            default -> new BigDecimal("0.25");
        };
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        return denominator == 0
                ? BigDecimal.ONE
                : BigDecimal.valueOf(numerator)
                        .divide(BigDecimal.valueOf(denominator), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal clamp(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    private record Requirement(String name, boolean available) {
    }

    public record DataQualityAssessment(
            BigDecimal score,
            List<String> missingData,
            List<String> staleEvidenceIds,
            List<String> sourceConflicts,
            BigDecimal requiredDataCoverage,
            BigDecimal freshnessCoverage,
            BigDecimal primarySourceCoverage,
            BigDecimal consistencyScore
    ) {
    }
}
