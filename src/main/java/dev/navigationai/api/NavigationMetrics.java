package dev.navigationai.api;

/** Immutable runtime performance snapshot for diagnostics and backpressure. */
public record NavigationMetrics(
        long serverTicks,
        long lastTickNanos,
        long totalTickNanos,
        long maxTickNanos,
        long snapshots,
        long snapshotNanos,
        long pathSearchesSubmitted,
        long pathSearchesCompleted,
        long pathSearchesCancelled,
        long pathSearchesRejected,
        long backpressureDeferrals,
        long nodeBudgetDeferrals,
        long pathSearchNanos,
        long pathNodesVisited,
        long replans,
        int queuedSearches,
        int activeSearches) {

    public double averageTickMillis() {
        return serverTicks == 0 ? 0.0 : totalTickNanos / (double) serverTicks / 1_000_000.0;
    }

    public double averagePathSearchMillis() {
        return pathSearchesCompleted == 0 ? 0.0
                : pathSearchNanos / (double) pathSearchesCompleted / 1_000_000.0;
    }

    public double averageSnapshotMillis() {
        return snapshots == 0 ? 0.0 : snapshotNanos / (double) snapshots / 1_000_000.0;
    }
}
