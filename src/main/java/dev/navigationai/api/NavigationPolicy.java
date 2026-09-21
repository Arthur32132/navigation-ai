package dev.navigationai.api;

/** Cost and safety controls used by path planning. */
public record NavigationPolicy(
        boolean allowBreaking,
        boolean allowPlacing,
        boolean allowWater,
        boolean allowChunkGeneration,
        int maxSearchNodes,
        int maxPathLength,
        int snapshotRadius,
        double dangerCost) {
    public static NavigationPolicy safeDefaults() {
        return new NavigationPolicy(false, false, true, false, 40_000, 2_048, 32, 100.0);
    }

    public NavigationPolicy {
        if (maxSearchNodes < 128) throw new IllegalArgumentException("maxSearchNodes must be >= 128");
        if (maxPathLength < 1) throw new IllegalArgumentException("maxPathLength must be positive");
        if (snapshotRadius < 4) throw new IllegalArgumentException("snapshotRadius must be >= 4");
        if (dangerCost < 0) throw new IllegalArgumentException("dangerCost cannot be negative");
    }
}
