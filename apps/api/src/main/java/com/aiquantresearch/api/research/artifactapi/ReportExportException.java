package com.aiquantresearch.api.research.artifactapi;

import com.aiquantresearch.api.research.application.ResearchApplicationException;
import java.util.UUID;

final class ReportExportException extends ResearchApplicationException {

    ReportExportException(UUID researchId) {
        super(
                "EXPORT_RENDER_FAILED",
                "The requested report format could not be rendered",
                true,
                researchId
        );
    }
}
