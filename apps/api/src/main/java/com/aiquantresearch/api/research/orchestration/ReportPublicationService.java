package com.aiquantresearch.api.research.orchestration;

import com.aiquantresearch.api.research.worker.QueueClaim;
import com.fasterxml.jackson.databind.node.ObjectNode;

public interface ReportPublicationService {

    void publish(
            QueueClaim claim,
            StepExecutionResult result,
            String outputHash,
            ObjectNode outputManifest
    );
}
