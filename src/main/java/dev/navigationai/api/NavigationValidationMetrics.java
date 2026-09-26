package dev.navigationai.api;

/** Cost of live transition validation, including cache checks and deferred captures. */
public record NavigationValidationMetrics(long calls, long totalNanos, long deferredCalls) {
    public double averageMillis() { return calls==0?0:totalNanos/(double)calls/1_000_000.0; }
}
