package dev.navigationai.runtime;

/** Accounts only infrastructure waiting, with an independent real-time circuit breaker. */
final class InfrastructureWaitTracker {
    static final long MAX_WAIT_NANOS = 30_000_000_000L;
    private long lastTick, lastNanos, waitingSince, ticks;
    private boolean initialized, waiting;
    void sample(long tick,long nanos,boolean pending) {
        if(initialized&&waiting)ticks+=Math.max(0,tick-lastTick);
        if(pending&&(!initialized||!waiting))waitingSince=nanos;
        initialized=true;waiting=pending;lastTick=tick;lastNanos=nanos;
    }
    long ticks() { return ticks; }
    boolean expired() { return waiting&&lastNanos-waitingSince>=MAX_WAIT_NANOS; }
}
