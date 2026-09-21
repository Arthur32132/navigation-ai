package dev.navigationai.runtime;

import dev.navigationai.api.NavigationMetrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NavigationMetricsCollectorTest {
    @Test
    void createsAnImmutableRuntimeSnapshotWithAverages() {
        NavigationMetricsCollector collector = new NavigationMetricsCollector();
        collector.recordServerTick(2_000_000L);
        collector.recordServerTick(4_000_000L);
        collector.recordSnapshot(1_000_000L);
        collector.recordPathSearchSubmitted();
        collector.recordPathSearch(12, 3_000_000L);
        collector.recordPathSearchCancelled();
        collector.recordPathSearchRejected();
        collector.recordBackpressureDeferral();
        collector.recordNodeBudgetDeferral();
        collector.recordReplan();

        NavigationMetrics metrics = collector.snapshot(3, 1);

        assertEquals(2, metrics.serverTicks());
        assertEquals(4_000_000L, metrics.lastTickNanos());
        assertEquals(4_000_000L, metrics.maxTickNanos());
        assertEquals(3.0, metrics.averageTickMillis());
        assertEquals(1.0, metrics.averageSnapshotMillis());
        assertEquals(3.0, metrics.averagePathSearchMillis());
        assertEquals(1, metrics.pathSearchesSubmitted());
        assertEquals(1, metrics.pathSearchesCompleted());
        assertEquals(1, metrics.pathSearchesCancelled());
        assertEquals(1, metrics.pathSearchesRejected());
        assertEquals(1, metrics.backpressureDeferrals());
        assertEquals(1, metrics.nodeBudgetDeferrals());
        assertEquals(12, metrics.pathNodesVisited());
        assertEquals(1, metrics.replans());
        assertEquals(3, metrics.queuedSearches());
        assertEquals(1, metrics.activeSearches());
    }
}
