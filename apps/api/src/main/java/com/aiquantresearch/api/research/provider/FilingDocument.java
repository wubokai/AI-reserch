package com.aiquantresearch.api.research.provider;

import java.time.LocalDate;

public record FilingDocument(
        String documentId,
        String formType,
        LocalDate filingDate,
        String title,
        String summary,
        String contentHtml
) {
}
