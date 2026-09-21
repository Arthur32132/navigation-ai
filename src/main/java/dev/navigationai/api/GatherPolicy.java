package dev.navigationai.api;

public record GatherPolicy(int radius, int timeoutTicks, int maxAttemptsPerBlock) {
    public static GatherPolicy defaults() {
        return new GatherPolicy(16, 6_000, 3);
    }

    public GatherPolicy {
        if (radius < 1 || radius > 32) throw new IllegalArgumentException("radius must be between 1 and 32");
        if (timeoutTicks < 1) throw new IllegalArgumentException("timeoutTicks must be positive");
        if (maxAttemptsPerBlock < 1) throw new IllegalArgumentException("maxAttemptsPerBlock must be positive");
    }
}
