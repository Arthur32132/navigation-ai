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
        double dangerCost,
        int maxFallDistance) {
    public static final int MAX_SEARCH_NODES = 200_000;
    public static final int MAX_PATH_LENGTH = 16_384;
    public static final int MAX_SNAPSHOT_RADIUS = 64;

    /** Backwards-compatible policy constructor using the safe fall limit. */
    public NavigationPolicy(boolean allowBreaking, boolean allowPlacing, boolean allowWater,
                            boolean allowChunkGeneration, int maxSearchNodes, int maxPathLength,
                            int snapshotRadius, double dangerCost) {
        this(allowBreaking, allowPlacing, allowWater, allowChunkGeneration,
                maxSearchNodes, maxPathLength, snapshotRadius, dangerCost, 3);
    }

    public static NavigationPolicy safeDefaults() {
        return new NavigationPolicy(false, false, true, false, 40_000, 2_048, 32, 100.0, 3);
    }

    public NavigationPolicy {
        if (maxSearchNodes < 128) throw new IllegalArgumentException("maxSearchNodes must be >= 128");
        if (maxSearchNodes > MAX_SEARCH_NODES) {
            throw new IllegalArgumentException("maxSearchNodes must be <= " + MAX_SEARCH_NODES);
        }
        if (maxPathLength < 1) throw new IllegalArgumentException("maxPathLength must be positive");
        if (maxPathLength > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("maxPathLength must be <= " + MAX_PATH_LENGTH);
        }
        if (snapshotRadius < 4) throw new IllegalArgumentException("snapshotRadius must be >= 4");
        if (snapshotRadius > MAX_SNAPSHOT_RADIUS) {
            throw new IllegalArgumentException("snapshotRadius must be <= " + MAX_SNAPSHOT_RADIUS);
        }
        if (!Double.isFinite(dangerCost) || dangerCost < 0) {
            throw new IllegalArgumentException("dangerCost must be finite and non-negative");
        }
        if (maxFallDistance < 0 || maxFallDistance > 16) {
            throw new IllegalArgumentException("maxFallDistance must be between 0 and 16");
        }
    }
}
