package dev.navigationai.api;

public record MiningResult(MiningStatus status, String reason, boolean blockBroken, long durationNanos) {
    public static MiningResult success(long durationNanos) {
        return new MiningResult(MiningStatus.SUCCESS, "block_broken", true, durationNanos);
    }

    public static MiningResult failure(String reason, long durationNanos) {
        return new MiningResult(MiningStatus.FAILED, reason, false, durationNanos);
    }

    public static MiningResult cancelled() {
        return new MiningResult(MiningStatus.CANCELLED, "cancelled", false, 0);
    }
}
