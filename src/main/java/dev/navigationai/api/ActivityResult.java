package dev.navigationai.api;

public record ActivityResult(ActivityStatus status, String reason, int completedSteps,
                             int interruptions, long durationNanos) { }
