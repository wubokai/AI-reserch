package com.aiquantresearch.api.research.provider.runtime;

import java.util.function.Supplier;

public interface ProviderRuntime {

    <T> T execute(ProviderCall<T> call, Supplier<T> loader);

    void recordRetry(String provider, String reason);

    static ProviderRuntime direct() {
        return new ProviderRuntime() {
            @Override
            public <T> T execute(ProviderCall<T> call, Supplier<T> loader) {
                return loader.get();
            }

            @Override
            public void recordRetry(String provider, String reason) {
                // Unit-level adapter tests exercise retry behavior without infrastructure metrics.
            }
        };
    }
}
