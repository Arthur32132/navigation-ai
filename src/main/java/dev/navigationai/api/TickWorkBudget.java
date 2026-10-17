package dev.navigationai.api;

/** Real CPU allowances; gameplay durations must remain in game ticks. */
public final class TickWorkBudget {
    private TickWorkBudget() { }
    public static final int HAZARD_REPATH_TICKS = 5;
    private static long interval(long targetNanos) { return Math.clamp(targetNanos, 100_000L, 50_000_000L); }
    public static boolean overloaded(long targetNanos,long measuredNanos) {
        return measuredNanos > interval(targetNanos) * 3 / 2;
    }
    public static long planningNanos(long targetNanos,long measuredNanos) {
        long interval=interval(targetNanos), budget=Math.min(5_000_000L,interval/5);
        if(overloaded(targetNanos,measuredNanos))budget=budget*2/5;
        else if(measuredNanos>interval)budget=budget*3/5;
        return Math.max(50_000L,budget);
    }
    public static long residentDecisionNanos(long targetNanos) {
        return Math.max(50_000L,interval(targetNanos)/25);
    }
}
