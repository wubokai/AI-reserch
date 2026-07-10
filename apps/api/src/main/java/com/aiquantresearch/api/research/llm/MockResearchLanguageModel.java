package com.aiquantresearch.api.research.llm;

import com.aiquantresearch.api.research.application.CanonicalHashService;
import com.aiquantresearch.api.research.report.DeterministicMockReportGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class MockResearchLanguageModel implements ResearchLanguageModel {

    private static final String MODEL_NAME = "deterministic-report-v1";

    private final DeterministicMockReportGenerator reportGenerator;
    private final CanonicalHashService hashService;
    private final LlmProperties properties;

    public MockResearchLanguageModel(
            DeterministicMockReportGenerator reportGenerator,
            CanonicalHashService hashService,
            LlmProperties properties
    ) {
        this.reportGenerator = reportGenerator;
        this.hashService = hashService;
        this.properties = properties;
    }

    @Override
    public ResearchLanguageModelResult generateReport(ResearchLanguageModelRequest request) {
        return deterministic(request, "MOCK", MODEL_NAME, null, false);
    }

    public ResearchLanguageModelResult safeFallback(
            ResearchLanguageModelRequest request,
            String reasonCode
    ) {
        return deterministic(
                request,
                "DETERMINISTIC_FALLBACK",
                "deterministic-safe-fallback-v1",
                reasonCode,
                true
        );
    }

    private ResearchLanguageModelResult deterministic(
            ResearchLanguageModelRequest request,
            String provider,
            String modelName,
            String reasonCode,
            boolean partial
    ) {
        JsonNode report = reportGenerator.generate(
                request.context(),
                request.sources(),
                request.quantResults(),
                request.evidence(),
                request.reportVersion()
        );
        String responseHash = hashService.hash(report);
        String requestHash = hashService.hashText(String.join("|",
                provider,
                modelName,
                properties.promptVersion(),
                properties.schemaVersion(),
                request.context().researchId().toString(),
                Integer.toString(request.reportVersion()),
                responseHash,
                reasonCode == null ? "NONE" : reasonCode
        ));
        return new ResearchLanguageModelResult(
                report,
                new LlmCallAudit(
                        provider,
                        modelName,
                        properties.promptVersion(),
                        properties.schemaVersion(),
                        requestHash,
                        responseHash,
                        LlmUsage.empty(),
                        BigDecimal.ZERO,
                        0,
                        "SUCCEEDED",
                        null,
                        true,
                        null,
                        "mock_zero_cost_v1",
                        null,
                        0
                ),
                partial,
                partial
                        ? java.util.List.of(
                                "LLM_FINAL_SUMMARY_FAILED_SAFE_FALLBACK",
                                "LLM_FAILURE_CODE:" + reasonCode
                        )
                        : java.util.List.of()
        );
    }
}
