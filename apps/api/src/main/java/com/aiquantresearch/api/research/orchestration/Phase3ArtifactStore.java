package com.aiquantresearch.api.research.orchestration;

import com.aiquantresearch.api.research.llm.LlmCallAudit;
import com.aiquantresearch.api.research.worker.QueueClaim;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Phase3ArtifactStore {

    ResearchExecutionContext context(UUID researchId);

    Optional<StoredSource> source(UUID researchId, String purpose);

    List<StoredSource> sources(UUID researchId);

    List<StoredQuantResult> quantResults(UUID researchId);

    List<StoredEvidence> evidence(UUID researchId);

    Optional<JsonNode> generatedReport(UUID researchId);

    int nextReportVersion(UUID researchId);

    StoredSource persistSource(
            QueueClaim claim,
            SourceRegistration registration,
            JsonNode payload
    );

    List<StoredQuantResult> persistQuantMetrics(
            QueueClaim claim,
            JsonNode analyticsResponse
    );

    List<StoredEvidence> persistEvidence(
            QueueClaim claim,
            List<EvidenceDraft> drafts
    );

    UUID persistLlmCall(QueueClaim claim, LlmCallAudit audit);
}
