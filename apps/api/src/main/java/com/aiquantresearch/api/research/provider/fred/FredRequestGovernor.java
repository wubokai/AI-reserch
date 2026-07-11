package com.aiquantresearch.api.research.provider.fred;

import java.util.concurrent.locks.LockSupport;

final class FredRequestGovernor {

    private final long intervalNanos;
    private long nextRequestNanos;

    FredRequestGovernor(int maxRequestsPerSecond) {
        intervalNanos = 1_000_000_000L / maxRequestsPerSecond;
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
