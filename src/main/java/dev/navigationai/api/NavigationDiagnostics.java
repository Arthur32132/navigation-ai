package dev.navigationai.api;

/** Per-request measurements, including all local segments and replans. Tick latency uses server ticks. */
public record NavigationDiagnostics(long ticksElapsed, long ticksToFirstMovement, int replans,
                                    float damageTaken, long reusedSnapshotCells,
                                    boolean completed, long ticksWithoutMovement) {
    public static NavigationDiagnostics unavailable() {
        return new NavigationDiagnostics(0, -1, 0, 0, 0, false, 0);
    }
}
