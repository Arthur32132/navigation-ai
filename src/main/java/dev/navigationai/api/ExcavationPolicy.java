package dev.navigationai.api;

/** Excavations never place blocks, generate chunks, open containers, or expose fluids. */
public record ExcavationPolicy(int maxBlocks, int timeoutTicks, int minRemainingDurability,
                               boolean collectDrops, boolean returnToEntrance) {
    public static final int MAX_BLOCKS = 4_096;
    public static final int MAX_TIMEOUT_TICKS = 72_000;

    public static ExcavationPolicy defaults() {
        return new ExcavationPolicy(512, 24_000, 8, true, true);
    }

    public ExcavationPolicy {
        if (maxBlocks < 0 || maxBlocks > MAX_BLOCKS) throw new IllegalArgumentException("maxBlocks must be between 0 and " + MAX_BLOCKS);
        if (timeoutTicks < 1 || timeoutTicks > MAX_TIMEOUT_TICKS) throw new IllegalArgumentException("timeoutTicks must be between 1 and " + MAX_TIMEOUT_TICKS);
        if (minRemainingDurability < 1 || minRemainingDurability > 65_535) {
            throw new IllegalArgumentException("minRemainingDurability must be between 1 and 65535");
        }
    }
}
