package dev.navigationai.api;

public record NavigationResult(NavigationStatus status, String reason, int nodesVisited, long durationNanos) {
    public static NavigationResult success(int nodes, long duration) { return new NavigationResult(NavigationStatus.SUCCESS, "reached_goal", nodes, duration); }
    public static NavigationResult failure(String reason, int nodes, long duration) { return new NavigationResult(NavigationStatus.FAILED, reason, nodes, duration); }
}
