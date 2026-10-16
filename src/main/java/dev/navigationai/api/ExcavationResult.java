package dev.navigationai.api;

/** Progress remains available on failure and cancellation; brokenBlocks counts this run only. */
public record ExcavationResult(ExcavationStatus status, String reason, int completedSegments,
                               int brokenBlocks, boolean returnedToEntrance, long durationNanos) { }
