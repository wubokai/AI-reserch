package com.aiquantresearch.api.research.provider.runtime;

public record ProviderCall<T>(
        String provider,
        String circuitName,
        String schemaVersion,
        String subject,
        Class<T> resultType
) {
}
