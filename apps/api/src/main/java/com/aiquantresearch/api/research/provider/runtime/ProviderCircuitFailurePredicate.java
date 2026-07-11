package com.aiquantresearch.api.research.provider.runtime;

import com.aiquantresearch.api.research.provider.ProviderAccessException;
import java.util.function.Predicate;

public class ProviderCircuitFailurePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        return throwable instanceof ProviderAccessException exception
                && exception.retryable();
    }
}
