package dev.navigationai.runtime;

import dev.navigationai.api.NavigationMetrics;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Thread-safe counters shared by the server thread and path workers. */
final class NavigationMetricsCollector {
    private final LongAdder serverTicks = new LongAdder();
    private final LongAdder totalTickNanos = new LongAdder();
    private final AtomicLong lastTickNanos = new AtomicLong();
    private final AtomicLong maxTickNanos = new AtomicLong();
    private final LongAdder snapshots = new LongAdder();
    private final LongAdder snapshotNanos = new LongAdder();
    private final LongAdder pathSearchesSubmitted = new LongAdder();
    private final LongAdder pathSearchesCompleted = new LongAdder();
    private final LongAdder pathSearchesCancelled = new LongAdder();
    private final LongAdder pathSearchesRejected = new LongAdder();
    private final LongAdder backpressureDeferrals = new LongAdder();
    private final LongAdder nodeBudgetDeferrals = new LongAdder();
    private final LongAdder pathSearchNanos = new LongAdder();
    private final LongAdder pathNodesVisited = new LongAdder();
    private final LongAdder replans = new LongAdder();

    void recordServerTick(long durationNanos) {
        serverTicks.increment();
        totalTickNanos.add(durationNanos);
        lastTickNanos.set(durationNanos);
        maxTickNanos.accumulateAndGet(durationNanos, Math::max);
    }

    void recordSnapshot(long durationNanos) {
        snapshots.increment();
        snapshotNanos.add(durationNanos);
    }

    void recordPathSearchSubmitted() { pathSearchesSubmitted.increment(); }
    void recordPathSearchCancelled() { pathSearchesCancelled.increment(); }
    void recordPathSearchRejected() { pathSearchesRejected.increment(); }
    void recordBackpressureDeferral() { backpressureDeferrals.increment(); }
    void recordNodeBudgetDeferral() { nodeBudgetDeferrals.increment(); }

    void recordPathSearch(long nodesVisited, long durationNanos) {
        pathSearchesCompleted.increment();
        pathSearchNanos.add(durationNanos);
        pathNodesVisited.add(nodesVisited);
    }

    void recordReplan() { replans.increment(); }

    NavigationMetrics snapshot(int queuedSearches, int activeSearches) {
        return new NavigationMetrics(
                serverTicks.sum(),
                lastTickNanos.get(),
                totalTickNanos.sum(),
                maxTickNanos.get(),
                snapshots.sum(),
                snapshotNanos.sum(),
                pathSearchesSubmitted.sum(),
                pathSearchesCompleted.sum(),
                pathSearchesCancelled.sum(),
                pathSearchesRejected.sum(),
                backpressureDeferrals.sum(),
                nodeBudgetDeferrals.sum(),
                pathSearchNanos.sum(),
                pathNodesVisited.sum(),
                replans.sum(),
                queuedSearches,
                activeSearches);
    }
}
