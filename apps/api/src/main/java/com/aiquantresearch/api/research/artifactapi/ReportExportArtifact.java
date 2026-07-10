package com.aiquantresearch.api.research.artifactapi;

import com.aiquantresearch.api.shared.domain.DataMode;

record ReportExportArtifact(
        byte[] content,
        String contentHash,
        ReportExportFormat format,
        String filename,
        int reportVersion,
        DataMode dataMode
) {
    ReportExportArtifact {
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
