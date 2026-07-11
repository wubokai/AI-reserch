package com.aiquantresearch.api.research.report;

record ReportSourceAttribution(
        String sourceName,
        String sourceUrl,
        String sourceType,
        String statement,
        String licensePolicyVersion
) {
}
