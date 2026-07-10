package com.aiquantresearch.api.research.llm;

public enum LlmTaskType {
    RESEARCH_PLAN("research-plan.schema.json", "research_plan_v1"),
    FILING_ANALYSIS("filing-analysis.schema.json", "filing_analysis_v1"),
    FUNDAMENTAL_NARRATIVE("fundamental-narrative.schema.json", "fundamental_narrative_v1"),
    RISK_ANALYSIS("risk-analysis.schema.json", "risk_analysis_v1"),
    FINAL_REPORT("research-report.schema.json", "research_report_v1"),
    VALIDATION("validation-result.schema.json", "validation_result_v1");

    private final String resourceName;
    private final String schemaVersion;

    LlmTaskType(String resourceName, String schemaVersion) {
        this.resourceName = resourceName;
        this.schemaVersion = schemaVersion;
    }

    public String resourceName() {
        return resourceName;
    }

    public String schemaVersion() {
        return schemaVersion;
    }
}
