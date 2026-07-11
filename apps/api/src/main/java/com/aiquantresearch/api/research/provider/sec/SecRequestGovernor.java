package com.aiquantresearch.api.research.provider.sec;

import java.util.concurrent.locks.LockSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
final class SecRequestGovernor {

    private final long intervalNanos;
    private long nextRequestNanos;

    @Autowired
    SecRequestGovernor(SecEdgarProperties properties) {
        this(properties.maxRequestsPerSecond());
    }

    SecRequestGovernor(int maxRequestsPerSecond) {
        this.intervalNanos = 1_000_000_000L / maxRequestsPerSecond;
    }

    synchronized void acquire() {
        long now = System.nanoTime();
        long waitNanos = nextRequestNanos - now;
        if (waitNanos > 0) {
            LockSupport.parkNanos(waitNanos);
            now = System.nanoTime();
        }
        nextRequestNanos = Math.max(now, nextRequestNanos) + intervalNanos;
    }
}
