package dev.navigationai.api;

public record GatherResult(
        GatherStatus status,
        String reason,
        int collectedItems,
        int brokenBlocks,
        long durationNanos) {
}
