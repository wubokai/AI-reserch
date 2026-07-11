package com.aiquantresearch.api.research.provider;

import java.time.LocalDate;

public record FilingDocument(
        String documentId,
        String accessionNumber,
        String formType,
        LocalDate filingDate,
        LocalDate reportPeriod,
        String title,
        String summary,
        String sourceUrl,
        String contentHtml
) {

    public FilingDocument(
            String documentId,
            String formType,
            LocalDate filingDate,
            String title,
            String summary,
            String contentHtml
    ) {
        this(documentId, null, formType, filingDate, null, title, summary, null, contentHtml);
    }
}
