package com.aiquantresearch.api.research.report;

import com.aiquantresearch.api.research.orchestration.ResearchExecutionContext;
import com.aiquantresearch.api.research.orchestration.StoredEvidence;
import com.aiquantresearch.api.research.orchestration.StoredQuantResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ReportRepairService {

    private static final Set<String> DETERMINISTICALLY_REPAIRABLE_CODES = Set.of(
            "CLAIM_CONFIDENCE_MISMATCH",
            "DATA_QUALITY_SCORE_MISMATCH",
            "DATA_QUALITY_MISSING_DATA_MISMATCH",
            "STALE_EVIDENCE_DISCLOSURE_MISMATCH",
            "STALE_EVIDENCE_DUPLICATE"
    );

    public RepairResult repairOnce(
            JsonNode candidate,
            List<String> validationWarnings,
            List<StoredEvidence> evidence,
            List<StoredQuantResult> calculations,
            ResearchExecutionContext context
    ) {
        if (candidate == null || !candidate.isObject()) {
            return new RepairResult(candidate, List.of(), false);
        }
        ObjectNode repaired = candidate.deepCopy();
        Map<String, String> claimPaths = new HashMap<>();
        collectClaimPaths(repaired, "$", claimPaths);
        Set<String> unsafeClaimIds = new LinkedHashSet<>();
        for (String warning : validationWarnings) {
            int delimiter = warning.indexOf(':');
            String code = delimiter < 0 ? warning : warning.substring(0, delimiter);
            String path = delimiter < 0 ? "" : warning.substring(delimiter + 1);
            if (DETERMINISTICALLY_REPAIRABLE_CODES.contains(code)) {
                continue;
            }
            claimPaths.forEach((claimPath, claimId) -> {
                if (path.equals(claimPath) || path.startsWith(claimPath + ".")) {
                    unsafeClaimIds.add(claimId);
                }
            });
        }
        pruneUnsafeClaims(repaired, unsafeClaimIds);
        repairConfidence(repaired, evidence);
        repairDataQuality(repaired, evidence, calculations, context, unsafeClaimIds);
        return new RepairResult(repaired, List.copyOf(unsafeClaimIds), true);
    }

    private static void collectClaimPaths(
            JsonNode node,
            String path,
            Map<String, String> result
    ) {
        if (isClaim(node)) {
            result.put(path, node.path("id").asText());
            return;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                collectClaimPaths(node.get(index), path + "[" + index + "]", result);
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectClaimPaths(
                    entry.getValue(), path + "." + entry.getKey(), result
            ));
        }
    }

    private static void pruneUnsafeClaims(JsonNode node, Set<String> unsafeIds) {
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int index = array.size() - 1; index >= 0; index--) {
                JsonNode item = array.get(index);
                boolean directClaim = isClaim(item)
                        && unsafeIds.contains(item.path("id").asText());
                boolean wrappedClaim = item.isObject()
                        && isClaim(item.path("claim"))
                        && unsafeIds.contains(item.path("claim").path("id").asText());
                if (directClaim || wrappedClaim) {
                    array.remove(index);
                } else {
                    pruneUnsafeClaims(item, unsafeIds);
                }
            }
        } else if (node.isObject()) {
            node.elements().forEachRemaining(child -> pruneUnsafeClaims(child, unsafeIds));
        }
    }

    private static void repairConfidence(JsonNode node, List<StoredEvidence> evidence) {
        Map<String, StoredEvidence> evidenceById = new HashMap<>();
        evidence.forEach(item -> evidenceById.put(item.publicId(), item));
        boolean conflicts = node.path("dataQuality").path("sourceConflicts").isArray()
                && !node.path("dataQuality").path("sourceConflicts").isEmpty();
        visitClaims(node, claim -> {
            List<StoredEvidence> supporting = new ArrayList<>();
            claim.path("evidenceIds").forEach(id -> {
                StoredEvidence item = evidenceById.get(id.asText());
                if (item != null) {
                    supporting.add(item);
                }
            });
            claim.put(
                    "confidence",
                    EvidenceScoringPolicy.claimConfidence(
                            claim.path("claimType").asText(),
                            supporting,
                            conflicts
                    )
            );
        });
    }

    private static void repairDataQuality(
            ObjectNode report,
            List<StoredEvidence> evidence,
            List<StoredQuantResult> calculations,
            ResearchExecutionContext context,
            Set<String> prunedIds
    ) {
        ObjectNode quality = report.withObject("/dataQuality");
        List<String> conflicts = new ArrayList<>();
        quality.path("sourceConflicts").forEach(item -> conflicts.add(item.asText()));
        var assessment = EvidenceScoringPolicy.dataQuality(
                context,
                evidence,
                calculations,
                conflicts
        );
        quality.put("score", assessment.score());
        ArrayNode missing = quality.putArray("missingData");
        assessment.missingData().forEach(missing::add);
        ArrayNode stale = quality.putArray("staleEvidenceIds");
        assessment.staleEvidenceIds().forEach(stale::add);
        ArrayNode limitations = quality.withArray("limitations");
        if (!prunedIds.isEmpty()) {
            limitations.add("REPORT_REPAIR_PRUNED_UNSAFE_CLAIMS: "
                    + String.join(",", prunedIds));
        }
    }

    private static void visitClaims(JsonNode node, java.util.function.Consumer<ObjectNode> consumer) {
        if (isClaim(node)) {
            consumer.accept((ObjectNode) node);
            return;
        }
        if (node.isContainerNode()) {
            node.elements().forEachRemaining(child -> visitClaims(child, consumer));
        }
    }

    private static boolean isClaim(JsonNode node) {
        return node != null
                && node.isObject()
                && node.hasNonNull("id")
                && node.hasNonNull("statement")
                && node.hasNonNull("claimType");
    }

    public record RepairResult(
            JsonNode report,
            List<String> prunedClaimIds,
            boolean attempted
    ) {
    }
}
