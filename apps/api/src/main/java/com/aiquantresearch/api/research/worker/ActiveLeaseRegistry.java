package com.aiquantresearch.api.research.worker;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class ActiveLeaseRegistry {

    private final ConcurrentHashMap<UUID, ActiveLease> leases = new ConcurrentHashMap<>();

    public void register(QueueClaim claim) {
        leases.put(claim.attemptId(), new ActiveLease(claim, false, false, 0));
    }

    public void unregister(UUID attemptId) {
        leases.remove(attemptId);
    }

    public Collection<ActiveLease> snapshot() {
        return List.copyOf(leases.values());
    }

    public void markCancellationRequested(UUID attemptId) {
        leases.computeIfPresent(attemptId, (ignored, value) -> value.withCancellationRequested());
    }

    public void markStale(UUID attemptId) {
        leases.computeIfPresent(attemptId, (ignored, value) -> value.withStaleLease());
    }

    public void recordHeartbeatSuccess(UUID attemptId) {
        leases.computeIfPresent(attemptId, (ignored, value) -> value.withHeartbeatSuccess());
    }

    public int recordHeartbeatFailure(UUID attemptId) {
        AtomicInteger failures = new AtomicInteger();
        leases.computeIfPresent(attemptId, (ignored, value) -> {
            ActiveLease next = value.withHeartbeatFailure();
            failures.set(next.consecutiveHeartbeatFailures());
            return next;
        });
        return failures.get();
    }

    public boolean cancellationRequested(UUID attemptId) {
        ActiveLease lease = leases.get(attemptId);
        return lease != null && lease.cancellationRequested();
    }

    public boolean stale(UUID attemptId) {
        ActiveLease lease = leases.get(attemptId);
        return lease != null && lease.staleLease();
    }

    public record ActiveLease(
            QueueClaim claim,
            boolean cancellationRequested,
            boolean staleLease,
            int consecutiveHeartbeatFailures
    ) {
        ActiveLease withCancellationRequested() {
            return new ActiveLease(claim, true, staleLease, consecutiveHeartbeatFailures);
        }

        ActiveLease withStaleLease() {
            return new ActiveLease(claim, cancellationRequested, true, consecutiveHeartbeatFailures);
        }

        ActiveLease withHeartbeatSuccess() {
            return new ActiveLease(claim, cancellationRequested, staleLease, 0);
        }

        ActiveLease withHeartbeatFailure() {
            return new ActiveLease(
                    claim,
                    cancellationRequested,
                    staleLease,
                    Math.min(Integer.MAX_VALUE, consecutiveHeartbeatFailures + 1)
            );
        }
    }
}
