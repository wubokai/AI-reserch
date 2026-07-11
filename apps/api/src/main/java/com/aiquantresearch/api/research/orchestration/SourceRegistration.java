package com.aiquantresearch.api.research.orchestration;

import java.time.Instant;
import java.time.LocalDate;

public record SourceRegistration(
        String provider,
        String sourceType,
        String schemaVersion,
        String purpose,
        String externalSourceId,
        String sourceUrl,
        Instant retrievedAt,
        LocalDate effectiveDate,
        String rawDataHash,
        boolean primary,
        String freshnessStatus,
        boolean demoData,
        String licensePolicyVersion
) {
}
