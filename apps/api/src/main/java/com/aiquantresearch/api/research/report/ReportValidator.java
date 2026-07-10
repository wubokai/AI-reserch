package com.aiquantresearch.api.research.report;

import com.aiquantresearch.api.research.orchestration.ResearchExecutionContext;
import com.aiquantresearch.api.research.orchestration.StoredEvidence;
import com.aiquantresearch.api.research.orchestration.StoredQuantResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ReportValidator {

    private static final Pattern CLAIM_ID = Pattern.compile("^cl_[A-Za-z0-9_-]{1,64}$");
    private static final Pattern EVIDENCE_ID = Pattern.compile("^ev_[A-Za-z0-9_-]{1,64}$");
    private static final Pattern CALCULATION_ID = Pattern.compile("^calc_[A-Za-z0-9_-]{1,64}$");
    private static final Pattern DECIMAL = Pattern.compile("^-?[0-9]+(?:\\.[0-9]+)?$");
    private static final Pattern NON_NEGATIVE_DECIMAL = Pattern.compile("^[0-9]+(?:\\.[0-9]+)?$");
    private static final Pattern ISO_DATE = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern STATEMENT_NUMBER = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])-?\\d+(?:\\.\\d+)?%?(?![\\p{L}\\p{N}_])"
    );

    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "title", "symbol", "securityType", "locale", "asOfDate",
            "dataMode", "sections", "bullCase", "bearCase", "catalysts", "risks",
            "scenarioAnalysis", "dataQuality", "conclusion", "disclaimer"
    );
    private static final Set<String> CLAIM_FIELDS = Set.of(
            "id", "statement", "claimType", "materiality", "evidenceIds",
            "calculationIds", "numericReferences", "dateReferences", "confidence", "limitations"
    );
    private static final Set<String> REFERENCE_FIELDS = Set.of(
            "token", "normalizedValue", "unit", "sourceKind", "sourceId",
            "jsonPointer", "tolerance"
    );
    private static final Set<String> DATE_REFERENCE_FIELDS = Set.of(
            "token", "normalizedDate", "sourceKind", "sourceId", "jsonPointer"
    );
    private static final Set<String> CLAIM_TYPES = Set.of(
            "FACT", "CALCULATION", "INFERENCE", "OPINION"
    );
    private static final Set<String> RISK_CATEGORIES = Set.of(
            "BUSINESS", "FINANCIAL", "VALUATION", "MARKET", "REGULATORY",
            "EXECUTION", "DATA_QUALITY"
    );

    public ReportValidationResult validate(
            JsonNode report,
            List<StoredQuantResult> quantResults,
            List<StoredEvidence> evidence,
            ResearchExecutionContext context
    ) {
        Objects.requireNonNull(quantResults, "quantResults");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(context, "context");
        List<String> warnings = new ArrayList<>();
        if (report == null || !report.isObject()) {
            warnings.add("REPORT_NOT_OBJECT:$");
            return new ReportValidationResult(false, report, warnings, false);
        }

        Map<String, StoredQuantResult> calculations = quantResults.stream()
                .filter(item -> "AVAILABLE".equals(item.status()) && item.value() != null)
                .collect(Collectors.toMap(
                        StoredQuantResult::publicId,
                        item -> item,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<String, StoredQuantResult> calculationsByMetric = calculations.values().stream()
                .collect(Collectors.toMap(
                        StoredQuantResult::metricName,
                        item -> item,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<String, StoredEvidence> evidenceById = evidence.stream()
                .collect(Collectors.toMap(
                        StoredEvidence::publicId,
                        item -> item,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        boolean unresolvedConflict = report.path("dataQuality").path("sourceConflicts").isArray()
                && !report.path("dataQuality").path("sourceConflicts").isEmpty();

        exactFields(report, ROOT_FIELDS, "$", warnings);
        validateText(report, "schemaVersion", "$", 1, 100, warnings);
        if (!DeterministicMockReportGenerator.SCHEMA_VERSION.equals(
                report.path("schemaVersion").asText()
        )) {
            warn(warnings, "SCHEMA_VERSION_MISMATCH", "$.schemaVersion");
        }
        validateText(report, "title", "$", 1, 200, warnings);
        validateText(report, "symbol", "$", 1, 12, warnings);
        validateText(report, "securityType", "$", 1, 40, warnings);
        validateText(report, "locale", "$", 1, 10, warnings);
        validateText(report, "asOfDate", "$", 10, 10, warnings);
        validateText(report, "dataMode", "$", 1, 20, warnings);
        validateText(report, "disclaimer", "$", 1, 1000, warnings);

        if (!context.symbol().equals(report.path("symbol").asText())) {
            warn(warnings, "SYMBOL_CONTEXT_MISMATCH", "$.symbol");
        }
        if (!context.securityType().equals(report.path("securityType").asText())) {
            warn(warnings, "SECURITY_TYPE_CONTEXT_MISMATCH", "$.securityType");
        }
        if (!context.locale().equals(report.path("locale").asText())) {
            warn(warnings, "LOCALE_CONTEXT_MISMATCH", "$.locale");
        }
        if (!context.dataMode().name().equals(report.path("dataMode").asText())) {
            warn(warnings, "DATA_MODE_CONTEXT_MISMATCH", "$.dataMode");
        }
        validateAsOfDate(report, evidence, warnings);
        validateDemoDisclosure(report, warnings);

        Set<String> claimIds = new HashSet<>();
        int materialClaims = 0;
        JsonNode sections = report.path("sections");
        if (!sections.isArray() || sections.size() < 4 || sections.size() > 16) {
            warn(warnings, "SECTION_COUNT_INVALID", "$.sections");
        } else {
            Set<String> sectionIds = new HashSet<>();
            for (int index = 0; index < sections.size(); index++) {
                JsonNode section = sections.get(index);
                String path = "$.sections[" + index + "]";
                exactFields(section, Set.of("id", "heading", "claims", "transitionText"), path, warnings);
                validateText(section, "id", path, 2, 64, warnings);
                validateText(section, "heading", path, 1, 120, warnings);
                validateText(section, "transitionText", path, 0, 500, warnings);
                String sectionId = section.path("id").asText();
                if (!sectionIds.add(sectionId)) {
                    warn(warnings, "DUPLICATE_SECTION_ID", path + ".id");
                }
                materialClaims += validateClaimArray(
                        section.path("claims"),
                        path + ".claims",
                        calculations,
                        evidenceById,
                        claimIds,
                        unresolvedConflict,
                        30,
                        warnings
                );
            }
        }

        materialClaims += validateClaimArray(
                report.path("bullCase"), "$.bullCase", calculations, evidenceById,
                claimIds, unresolvedConflict, 8, warnings
        );
        materialClaims += validateClaimArray(
                report.path("bearCase"), "$.bearCase", calculations, evidenceById,
                claimIds, unresolvedConflict, 8, warnings
        );
        materialClaims += validateClaimArray(
                report.path("catalysts"), "$.catalysts", calculations, evidenceById,
                claimIds, unresolvedConflict, 12, warnings
        );
        materialClaims += validateRisks(
                report.path("risks"), calculations, evidenceById, claimIds,
                unresolvedConflict, warnings
        );
        materialClaims += validateScenario(
                report.path("scenarioAnalysis"), calculations, calculationsByMetric,
                evidenceById, claimIds, unresolvedConflict, warnings
        );
        materialClaims += validateClaimArray(
                report.path("conclusion"), "$.conclusion", calculations, evidenceById,
                claimIds, unresolvedConflict, 8, warnings
        );
        if (!report.path("conclusion").isArray() || report.path("conclusion").isEmpty()) {
            warn(warnings, "CONCLUSION_REQUIRED", "$.conclusion");
        }
        if (materialClaims == 0) {
            warn(warnings, "MATERIAL_CLAIM_REQUIRED", "$");
        }
        validateDataQuality(
                report.path("dataQuality"),
                evidence,
                evidenceById,
                quantResults,
                context,
                warnings
        );

        boolean valid = warnings.isEmpty();
        boolean declaredPartial = report.path("dataQuality").path("missingData").isArray()
                && !report.path("dataQuality").path("missingData").isEmpty();
        boolean rootUsable = DeterministicMockReportGenerator.SCHEMA_VERSION.equals(
                report.path("schemaVersion").asText()
        ) && report.path("sections").isArray();
        return new ReportValidationResult(
                valid,
                report,
                warnings,
                valid ? declaredPartial : rootUsable
        );
    }

    private static int validateClaimArray(
            JsonNode claims,
            String path,
            Map<String, StoredQuantResult> calculations,
            Map<String, StoredEvidence> evidence,
            Set<String> claimIds,
            boolean unresolvedConflict,
            int maxItems,
            List<String> warnings
    ) {
        if (!claims.isArray()) {
            warn(warnings, "CLAIM_ARRAY_REQUIRED", path);
            return 0;
        }
        if (claims.size() > maxItems) {
            warn(warnings, "CLAIM_ARRAY_TOO_LARGE", path);
        }
        int materialClaims = 0;
        for (int index = 0; index < claims.size(); index++) {
            JsonNode claim = claims.get(index);
            String claimPath = path + "[" + index + "]";
            if (validateClaim(
                    claim, claimPath, calculations, evidence, claimIds,
                    unresolvedConflict, warnings
            )) {
                materialClaims++;
            }
        }
        return materialClaims;
    }

    private static boolean validateClaim(
            JsonNode claim,
            String path,
            Map<String, StoredQuantResult> calculations,
            Map<String, StoredEvidence> evidence,
            Set<String> claimIds,
            boolean unresolvedConflict,
            List<String> warnings
    ) {
        if (!claim.isObject()) {
            warn(warnings, "CLAIM_NOT_OBJECT", path);
            return false;
        }
        exactFields(claim, CLAIM_FIELDS, path, warnings);
        validateText(claim, "id", path, 1, 67, warnings);
        validateText(claim, "statement", path, 1, 1200, warnings);
        validateText(claim, "claimType", path, 1, 20, warnings);
        validateText(claim, "materiality", path, 1, 20, warnings);
        String id = claim.path("id").asText();
        if (!CLAIM_ID.matcher(id).matches()) {
            warn(warnings, "CLAIM_ID_INVALID", path + ".id");
        } else if (!claimIds.add(id)) {
            warn(warnings, "DUPLICATE_CLAIM_ID", path + ".id");
        }
        String claimType = claim.path("claimType").asText();
        if (!CLAIM_TYPES.contains(claimType)) {
            warn(warnings, "CLAIM_TYPE_INVALID", path + ".claimType");
        }
        String materiality = claim.path("materiality").asText();
        if (!Set.of("MATERIAL", "SUPPORTING").contains(materiality)) {
            warn(warnings, "CLAIM_MATERIALITY_INVALID", path + ".materiality");
        }

        Set<String> claimEvidenceIds = validateIdArray(
                claim.path("evidenceIds"), path + ".evidenceIds", 1, 12,
                EVIDENCE_ID, evidence.keySet(), "EVIDENCE", warnings
        );
        Set<String> claimCalculationIds = validateIdArray(
                claim.path("calculationIds"), path + ".calculationIds", 0, 12,
                CALCULATION_ID, calculations.keySet(), "CALCULATION", warnings
        );
        if ("MATERIAL".equals(materiality) && claimEvidenceIds.isEmpty()) {
            warn(warnings, "MATERIAL_CLAIM_WITHOUT_EVIDENCE", path + ".evidenceIds");
        }
        if ("CALCULATION".equals(claimType) && claimCalculationIds.isEmpty()) {
            warn(warnings, "CALCULATION_CLAIM_WITHOUT_CALCULATION", path + ".calculationIds");
        }
        List<StoredEvidence> supportingEvidence = claimEvidenceIds.stream()
                .map(evidence::get)
                .filter(Objects::nonNull)
                .toList();
        if ("FACT".equals(claimType)
                && !supportingEvidence.isEmpty()
                && supportingEvidence.stream().allMatch(item -> Set.of("INFERENCE", "OPINION")
                        .contains(item.value().path("supportKind").asText()))) {
            warn(warnings, "FACT_SUPPORTED_ONLY_BY_INFERENCE", path + ".evidenceIds");
        }

        JsonNode references = claim.path("numericReferences");
        Set<String> numericTokens = new HashSet<>();
        if (!references.isArray()) {
            warn(warnings, "NUMERIC_REFERENCE_ARRAY_REQUIRED", path + ".numericReferences");
        } else {
            if (references.size() > 24) {
                warn(warnings, "NUMERIC_REFERENCE_ARRAY_TOO_LARGE", path + ".numericReferences");
            }
            for (int index = 0; index < references.size(); index++) {
                numericTokens.add(references.get(index).path("token").asText());
                validateNumericReference(
                        references.get(index),
                        path + ".numericReferences[" + index + "]",
                        claim.path("statement").asText(),
                        claimEvidenceIds,
                        claimCalculationIds,
                        calculations,
                        evidence,
                        warnings
                );
            }
        }
        validateStatementNumericCoverage(
                claim.path("statement").asText(),
                numericTokens,
                path + ".statement",
                warnings
        );
        validateDateReferences(
                claim.path("dateReferences"),
                path + ".dateReferences",
                claim.path("statement").asText(),
                claimEvidenceIds,
                claimCalculationIds,
                calculations,
                evidence,
                warnings
        );
        JsonNode confidence = claim.path("confidence");
        if (!confidence.isNumber()
                || confidence.decimalValue().compareTo(BigDecimal.ZERO) < 0
                || confidence.decimalValue().compareTo(BigDecimal.ONE) > 0) {
            warn(warnings, "CLAIM_CONFIDENCE_INVALID", path + ".confidence");
        } else {
            BigDecimal expected = EvidenceScoringPolicy.claimConfidence(
                    claimType,
                    supportingEvidence,
                    unresolvedConflict
            );
            if (confidence.decimalValue().subtract(expected).abs()
                    .compareTo(new BigDecimal("0.005")) > 0) {
                warn(warnings, "CLAIM_CONFIDENCE_MISMATCH", path + ".confidence");
            }
        }
        validateStringArray(claim.path("limitations"), path + ".limitations", 10, warnings);
        if (("INFERENCE".equals(claimType) || "OPINION".equals(claimType))
                && (!claim.path("limitations").isArray()
                || claim.path("limitations").isEmpty())) {
            warn(warnings, "INTERPRETIVE_CLAIM_LIMITATION_REQUIRED", path + ".limitations");
        }
        return "MATERIAL".equals(materiality);
    }

    private static void validateNumericReference(
            JsonNode reference,
            String path,
            String statement,
            Set<String> claimEvidenceIds,
            Set<String> claimCalculationIds,
            Map<String, StoredQuantResult> calculations,
            Map<String, StoredEvidence> evidence,
            List<String> warnings
    ) {
        if (!reference.isObject()) {
            warn(warnings, "NUMERIC_REFERENCE_NOT_OBJECT", path);
            return;
        }
        exactFields(reference, REFERENCE_FIELDS, path, warnings);
        for (String field : List.of(
                "token", "normalizedValue", "unit", "sourceKind", "sourceId",
                "jsonPointer", "tolerance"
        )) {
            validateText(reference, field, path, 1, field.equals("jsonPointer") ? 300 : 100, warnings);
        }
        String token = reference.path("token").asText();
        if (!statement.contains(token)) {
            warn(warnings, "NUMERIC_TOKEN_NOT_IN_STATEMENT", path + ".token");
        }
        String normalized = reference.path("normalizedValue").asText();
        String toleranceText = reference.path("tolerance").asText();
        if (!DECIMAL.matcher(normalized).matches()) {
            warn(warnings, "NORMALIZED_VALUE_INVALID", path + ".normalizedValue");
            return;
        }
        if (!NON_NEGATIVE_DECIMAL.matcher(toleranceText).matches()) {
            warn(warnings, "TOLERANCE_INVALID", path + ".tolerance");
            return;
        }

        String sourceKind = reference.path("sourceKind").asText();
        String sourceId = reference.path("sourceId").asText();
        String pointer = reference.path("jsonPointer").asText();
        if (!pointer.startsWith("/")) {
            warn(warnings, "JSON_POINTER_INVALID", path + ".jsonPointer");
            return;
        }
        JsonNode sourceValue;
        String expectedUnit;
        BigDecimal storedValue = null;
        if ("CALCULATION".equals(sourceKind)) {
            StoredQuantResult calculation = calculations.get(sourceId);
            if (calculation == null) {
                warn(warnings, "NUMERIC_CALCULATION_NOT_ALLOWED", path + ".sourceId");
                return;
            }
            if (!claimCalculationIds.contains(sourceId)) {
                warn(warnings, "NUMERIC_CALCULATION_NOT_CLAIMED", path + ".sourceId");
            }
            sourceValue = calculation.result();
            expectedUnit = calculation.unit();
            storedValue = calculation.value();
        } else if ("EVIDENCE".equals(sourceKind)) {
            StoredEvidence evidenceItem = evidence.get(sourceId);
            if (evidenceItem == null) {
                warn(warnings, "NUMERIC_EVIDENCE_NOT_ALLOWED", path + ".sourceId");
                return;
            }
            if (!claimEvidenceIds.contains(sourceId)) {
                warn(warnings, "NUMERIC_EVIDENCE_NOT_CLAIMED", path + ".sourceId");
            }
            sourceValue = evidenceItem.value();
            expectedUnit = evidenceItem.unit();
        } else {
            warn(warnings, "NUMERIC_SOURCE_KIND_INVALID", path + ".sourceKind");
            return;
        }
        if (expectedUnit == null || expectedUnit.isBlank()) {
            warn(warnings, "NUMERIC_UNIT_UNVERIFIABLE", path + ".unit");
        } else if (!expectedUnit.equals(reference.path("unit").asText())) {
            warn(warnings, "NUMERIC_UNIT_MISMATCH", path + ".unit");
        }
        JsonNode resolved = sourceValue == null ? null : sourceValue.at(pointer);
        if (resolved == null || resolved.isMissingNode() || (!resolved.isTextual() && !resolved.isNumber())) {
            warn(warnings, "NUMERIC_POINTER_UNRESOLVED", path + ".jsonPointer");
            return;
        }
        try {
            BigDecimal expected = new BigDecimal(normalized);
            BigDecimal actual = new BigDecimal(resolved.asText());
            BigDecimal tolerance = new BigDecimal(toleranceText);
            if (expected.subtract(actual).abs().compareTo(tolerance) > 0) {
                warn(warnings, "NUMERIC_VALUE_MISMATCH", path + ".normalizedValue");
            }
            if (storedValue != null
                    && expected.subtract(storedValue).abs().compareTo(tolerance) > 0) {
                warn(warnings, "NUMERIC_STORED_VALUE_MISMATCH", path + ".normalizedValue");
            }
        } catch (NumberFormatException exception) {
            warn(warnings, "NUMERIC_SOURCE_VALUE_INVALID", path + ".jsonPointer");
        }
    }

    private static void validateStatementNumericCoverage(
            String statement,
            Set<String> referencedTokens,
            String path,
            List<String> warnings
    ) {
        String withoutDates = ISO_DATE.matcher(statement).replaceAll("          ");
        var matcher = STATEMENT_NUMBER.matcher(withoutDates);
        while (matcher.find()) {
            if (!referencedTokens.contains(matcher.group())) {
                warn(warnings, "NUMERIC_TOKEN_UNREFERENCED", path);
            }
        }
    }

    private static void validateDateReferences(
            JsonNode references,
            String path,
            String statement,
            Set<String> claimEvidenceIds,
            Set<String> claimCalculationIds,
            Map<String, StoredQuantResult> calculations,
            Map<String, StoredEvidence> evidence,
            List<String> warnings
    ) {
        if (!references.isArray()) {
            warn(warnings, "DATE_REFERENCE_ARRAY_REQUIRED", path);
            return;
        }
        if (references.size() > 12) {
            warn(warnings, "DATE_REFERENCE_ARRAY_TOO_LARGE", path);
        }
        Set<String> referencedTokens = new HashSet<>();
        for (int index = 0; index < references.size(); index++) {
            JsonNode reference = references.get(index);
            String referencePath = path + "[" + index + "]";
            if (!reference.isObject()) {
                warn(warnings, "DATE_REFERENCE_NOT_OBJECT", referencePath);
                continue;
            }
            exactFields(reference, DATE_REFERENCE_FIELDS, referencePath, warnings);
            for (String field : DATE_REFERENCE_FIELDS) {
                validateText(reference, field, referencePath, 1, 300, warnings);
            }
            String token = reference.path("token").asText();
            String normalizedDate = reference.path("normalizedDate").asText();
            if (!referencedTokens.add(token)) {
                warn(warnings, "DATE_REFERENCE_DUPLICATE", referencePath + ".token");
            }
            if (!statement.contains(token)) {
                warn(warnings, "DATE_TOKEN_NOT_IN_STATEMENT", referencePath + ".token");
            }
            if (safeDate(normalizedDate) == null || !token.equals(normalizedDate)) {
                warn(warnings, "DATE_VALUE_INVALID", referencePath + ".normalizedDate");
            }
            String sourceId = reference.path("sourceId").asText();
            JsonNode sourceValue;
            if ("EVIDENCE".equals(reference.path("sourceKind").asText())) {
                StoredEvidence item = evidence.get(sourceId);
                if (item == null || !claimEvidenceIds.contains(sourceId)) {
                    warn(warnings, "DATE_EVIDENCE_NOT_ALLOWED", referencePath + ".sourceId");
                    continue;
                }
                sourceValue = item.value();
            } else if ("CALCULATION".equals(reference.path("sourceKind").asText())) {
                StoredQuantResult item = calculations.get(sourceId);
                if (item == null || !claimCalculationIds.contains(sourceId)) {
                    warn(warnings, "DATE_CALCULATION_NOT_ALLOWED", referencePath + ".sourceId");
                    continue;
                }
                sourceValue = item.result();
            } else {
                warn(warnings, "DATE_SOURCE_KIND_INVALID", referencePath + ".sourceKind");
                continue;
            }
            String pointer = reference.path("jsonPointer").asText();
            if (!pointer.startsWith("/")) {
                warn(warnings, "DATE_POINTER_INVALID", referencePath + ".jsonPointer");
                continue;
            }
            JsonNode resolved = sourceValue.at(pointer);
            if (!resolved.isTextual() || !normalizedDate.equals(resolved.asText())) {
                warn(warnings, "DATE_VALUE_MISMATCH", referencePath + ".normalizedDate");
            }
        }
        var dateMatcher = ISO_DATE.matcher(statement);
        while (dateMatcher.find()) {
            if (!referencedTokens.contains(dateMatcher.group())) {
                warn(warnings, "DATE_TOKEN_UNREFERENCED", path);
            }
        }
    }

    private static int validateRisks(
            JsonNode risks,
            Map<String, StoredQuantResult> calculations,
            Map<String, StoredEvidence> evidence,
            Set<String> claimIds,
            boolean unresolvedConflict,
            List<String> warnings
    ) {
        if (!risks.isArray()) {
            warn(warnings, "RISK_ARRAY_REQUIRED", "$.risks");
            return 0;
        }
        if (risks.size() > 24) {
            warn(warnings, "RISK_ARRAY_TOO_LARGE", "$.risks");
        }
        int material = 0;
        for (int index = 0; index < risks.size(); index++) {
            JsonNode risk = risks.get(index);
            String path = "$.risks[" + index + "]";
            exactFields(risk, Set.of("category", "claim"), path, warnings);
            if (!RISK_CATEGORIES.contains(risk.path("category").asText())) {
                warn(warnings, "RISK_CATEGORY_INVALID", path + ".category");
            }
            if (validateClaim(risk.path("claim"), path + ".claim", calculations,
                    evidence, claimIds, unresolvedConflict, warnings)) {
                material++;
            }
        }
        return material;
    }

    private static int validateScenario(
            JsonNode scenarioAnalysis,
            Map<String, StoredQuantResult> calculations,
            Map<String, StoredQuantResult> calculationsByMetric,
            Map<String, StoredEvidence> evidence,
            Set<String> claimIds,
            boolean unresolvedConflict,
            List<String> warnings
    ) {
        String path = "$.scenarioAnalysis";
        exactFields(
                scenarioAnalysis,
                Set.of("calculationId", "scenarios", "weightedImpliedPrice", "summaryClaims"),
                path,
                warnings
        );
        String calculationId = scenarioAnalysis.path("calculationId").asText();
        if (!CALCULATION_ID.matcher(calculationId).matches()
                || !calculations.containsKey(calculationId)) {
            warn(warnings, "SCENARIO_CALCULATION_NOT_ALLOWED", path + ".calculationId");
        }
        StoredQuantResult weightedMetric = calculationsByMetric.get("weighted_scenario_value");
        if (weightedMetric != null && !weightedMetric.publicId().equals(calculationId)) {
            warn(warnings, "SCENARIO_WEIGHTED_CALCULATION_MISMATCH", path + ".calculationId");
        }
        compareDecimalToMetric(
                scenarioAnalysis.path("weightedImpliedPrice"),
                weightedMetric,
                path + ".weightedImpliedPrice",
                warnings
        );

        JsonNode scenarios = scenarioAnalysis.path("scenarios");
        Set<String> names = new HashSet<>();
        BigDecimal probabilitySum = BigDecimal.ZERO;
        if (!scenarios.isArray() || scenarios.size() != 3) {
            warn(warnings, "SCENARIO_COUNT_INVALID", path + ".scenarios");
        } else {
            for (int index = 0; index < scenarios.size(); index++) {
                JsonNode scenario = scenarios.get(index);
                String scenarioPath = path + ".scenarios[" + index + "]";
                exactFields(
                        scenario,
                        Set.of(
                                "name", "probability", "revenueGrowth", "targetEbitdaMargin",
                                "evToEbitdaMultiple", "impliedEquityValue", "impliedPrice",
                                "upsideDownside"
                        ),
                        scenarioPath,
                        warnings
                );
                String name = scenario.path("name").asText();
                if (!Set.of("BULL", "BASE", "BEAR").contains(name) || !names.add(name)) {
                    warn(warnings, "SCENARIO_NAME_INVALID", scenarioPath + ".name");
                }
                for (String field : List.of(
                        "probability", "revenueGrowth", "targetEbitdaMargin",
                        "evToEbitdaMultiple", "impliedEquityValue", "impliedPrice",
                        "upsideDownside"
                )) {
                    if (!isDecimal(scenario.path(field))) {
                        warn(warnings, "SCENARIO_DECIMAL_INVALID", scenarioPath + "." + field);
                    }
                }
                if (isDecimal(scenario.path("probability"))) {
                    probabilitySum = probabilitySum.add(
                            new BigDecimal(scenario.path("probability").asText())
                    );
                }
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                compareDecimalToMetric(
                        scenario.path("impliedPrice"),
                        calculationsByMetric.get("scenario_" + lower + "_implied_price"),
                        scenarioPath + ".impliedPrice",
                        warnings
                );
                compareDecimalToMetric(
                        scenario.path("upsideDownside"),
                        calculationsByMetric.get("scenario_" + lower + "_upside_downside"),
                        scenarioPath + ".upsideDownside",
                        warnings
                );
            }
        }
        if (!names.equals(Set.of("BULL", "BASE", "BEAR"))) {
            warn(warnings, "SCENARIO_SET_INCOMPLETE", path + ".scenarios");
        }
        if (probabilitySum.subtract(BigDecimal.ONE).abs()
                .compareTo(new BigDecimal("0.00000001")) > 0) {
            warn(warnings, "SCENARIO_PROBABILITY_SUM_INVALID", path + ".scenarios");
        }
        return validateClaimArray(
                scenarioAnalysis.path("summaryClaims"),
                path + ".summaryClaims",
                calculations,
                evidence,
                claimIds,
                unresolvedConflict,
                6,
                warnings
        );
    }

    private static void compareDecimalToMetric(
            JsonNode value,
            StoredQuantResult metric,
            String path,
            List<String> warnings
    ) {
        if (!isDecimal(value)) {
            warn(warnings, "CALCULATION_VALUE_INVALID", path);
            return;
        }
        if (metric == null || metric.value() == null) {
            warn(warnings, "CALCULATION_METRIC_MISSING", path);
            return;
        }
        BigDecimal actual = new BigDecimal(value.asText());
        if (actual.compareTo(metric.value()) != 0) {
            warn(warnings, "CALCULATION_VALUE_MISMATCH", path);
        }
    }

    private static void validateDataQuality(
            JsonNode dataQuality,
            List<StoredEvidence> evidenceItems,
            Map<String, StoredEvidence> evidence,
            List<StoredQuantResult> calculations,
            ResearchExecutionContext context,
            List<String> warnings
    ) {
        String path = "$.dataQuality";
        exactFields(
                dataQuality,
                Set.of("score", "missingData", "staleEvidenceIds", "sourceConflicts", "limitations"),
                path,
                warnings
        );
        JsonNode score = dataQuality.path("score");
        if (!score.isNumber()
                || score.decimalValue().compareTo(BigDecimal.ZERO) < 0
                || score.decimalValue().compareTo(BigDecimal.ONE) > 0) {
            warn(warnings, "DATA_QUALITY_SCORE_INVALID", path + ".score");
        }
        validateStringArray(dataQuality.path("missingData"), path + ".missingData", 30, warnings);
        validateStringArray(dataQuality.path("sourceConflicts"), path + ".sourceConflicts", 30, warnings);
        validateStringArray(dataQuality.path("limitations"), path + ".limitations", 30, warnings);
        Set<String> staleIds = validateIdArray(
                dataQuality.path("staleEvidenceIds"), path + ".staleEvidenceIds", 0, 50,
                EVIDENCE_ID, evidence.keySet(), "STALE_EVIDENCE", warnings
        );
        if (staleIds.size() != dataQuality.path("staleEvidenceIds").size()) {
            warn(warnings, "STALE_EVIDENCE_DUPLICATE", path + ".staleEvidenceIds");
        }
        List<String> sourceConflicts = new ArrayList<>();
        dataQuality.path("sourceConflicts").forEach(item -> sourceConflicts.add(item.asText()));
        var expected = EvidenceScoringPolicy.dataQuality(
                context,
                evidenceItems,
                calculations,
                sourceConflicts
        );
        if (score.isNumber() && score.decimalValue().compareTo(expected.score()) != 0) {
            warn(warnings, "DATA_QUALITY_SCORE_MISMATCH", path + ".score");
        }
        if (!stringValues(dataQuality.path("missingData")).equals(expected.missingData())) {
            warn(warnings, "DATA_QUALITY_MISSING_DATA_MISMATCH", path + ".missingData");
        }
        if (!staleIds.equals(Set.copyOf(expected.staleEvidenceIds()))) {
            warn(warnings, "STALE_EVIDENCE_DISCLOSURE_MISMATCH", path + ".staleEvidenceIds");
        }
    }

    private static Set<String> validateIdArray(
            JsonNode values,
            String path,
            int minItems,
            int maxItems,
            Pattern pattern,
            Set<String> allowlist,
            String kind,
            List<String> warnings
    ) {
        Set<String> validIds = new HashSet<>();
        if (!values.isArray()) {
            warn(warnings, kind + "_ID_ARRAY_REQUIRED", path);
            return validIds;
        }
        if (values.size() < minItems || values.size() > maxItems) {
            warn(warnings, kind + "_ID_COUNT_INVALID", path);
        }
        for (int index = 0; index < values.size(); index++) {
            JsonNode value = values.get(index);
            String id = value.asText();
            if (!value.isTextual() || !pattern.matcher(id).matches()) {
                warn(warnings, kind + "_ID_INVALID", path + "[" + index + "]");
                continue;
            }
            if (!validIds.add(id)) {
                warn(warnings, kind + "_ID_DUPLICATE", path + "[" + index + "]");
            }
            if (!allowlist.contains(id)) {
                warn(warnings, kind + "_ID_NOT_ALLOWED", path + "[" + index + "]");
            }
        }
        return validIds;
    }

    private static void validateAsOfDate(
            JsonNode report,
            List<StoredEvidence> evidence,
            List<String> warnings
    ) {
        LocalDate reportDate;
        try {
            reportDate = LocalDate.parse(report.path("asOfDate").asText());
        } catch (DateTimeParseException exception) {
            warn(warnings, "AS_OF_DATE_INVALID", "$.asOfDate");
            return;
        }
        LocalDate latestEvidenceDate = evidence.stream()
                .map(item -> item.value().path("asOfDate").asText())
                .filter(value -> !value.isBlank())
                .map(ReportValidator::safeDate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
        if (latestEvidenceDate == null) {
            warn(warnings, "EVIDENCE_DATE_UNAVAILABLE", "$.asOfDate");
        } else if (!reportDate.equals(latestEvidenceDate)) {
            warn(warnings, "AS_OF_DATE_EVIDENCE_MISMATCH", "$.asOfDate");
        }
    }

    private static LocalDate safeDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static void validateDemoDisclosure(JsonNode report, List<String> warnings) {
        if (!report.toString().contains(DeterministicMockReportGenerator.DEMO_WATERMARK)) {
            warn(warnings, "DEMO_WATERMARK_MISSING", "$");
        }
        String disclaimer = report.path("disclaimer").asText();
        if (!disclaimer.contains(DeterministicMockReportGenerator.DEMO_WATERMARK)) {
            warn(warnings, "DEMO_DISCLAIMER_MISSING", "$.disclaimer");
        }
        if (!disclaimer.toLowerCase(java.util.Locale.ROOT).contains("not investment advice")) {
            warn(warnings, "INVESTMENT_ADVICE_DISCLAIMER_MISSING", "$.disclaimer");
        }
    }

    private static void exactFields(
            JsonNode node,
            Set<String> expected,
            String path,
            List<String> warnings
    ) {
        if (!node.isObject()) {
            warn(warnings, "OBJECT_REQUIRED", path);
            return;
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        for (String field : expected) {
            if (!actual.contains(field)) {
                warn(warnings, "REQUIRED_FIELD_MISSING", path + "." + field);
            }
        }
        for (String field : actual) {
            if (!expected.contains(field)) {
                warn(warnings, "UNKNOWN_FIELD", path + "." + field);
            }
        }
    }

    private static void validateText(
            JsonNode node,
            String field,
            String path,
            int minLength,
            int maxLength,
            List<String> warnings
    ) {
        JsonNode value = node.path(field);
        if (!value.isTextual()
                || value.asText().length() < minLength
                || value.asText().length() > maxLength) {
            warn(warnings, "TEXT_FIELD_INVALID", path + "." + field);
        }
    }

    private static void validateStringArray(
            JsonNode values,
            String path,
            int maxItems,
            List<String> warnings
    ) {
        if (!values.isArray()) {
            warn(warnings, "STRING_ARRAY_REQUIRED", path);
            return;
        }
        if (values.size() > maxItems) {
            warn(warnings, "STRING_ARRAY_TOO_LARGE", path);
        }
        for (int index = 0; index < values.size(); index++) {
            if (!values.get(index).isTextual() || values.get(index).asText().isBlank()) {
                warn(warnings, "STRING_ARRAY_ITEM_INVALID", path + "[" + index + "]");
            }
        }
    }

    private static List<String> stringValues(JsonNode values) {
        List<String> result = new ArrayList<>();
        if (values.isArray()) {
            values.forEach(item -> result.add(item.asText()));
        }
        return List.copyOf(result);
    }

    private static boolean isDecimal(JsonNode value) {
        return value.isTextual() && DECIMAL.matcher(value.asText()).matches();
    }

    private static void warn(List<String> warnings, String code, String path) {
        warnings.add(code + ":" + path);
    }
}
