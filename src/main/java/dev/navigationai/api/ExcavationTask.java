package dev.navigationai.api;

import java.util.concurrent.CompletableFuture;

/** Server-thread task. Cancelling leaves already mined blocks and collected items in the world. */
public interface ExcavationTask {
    ExcavationStatus status();
    ExcavationPhase phase();
    int completedSegments();
    int brokenBlocks();
    CompletableFuture<ExcavationResult> completion();
    void cancel();
    default boolean waitingForInfrastructure() { return false; }
    default long infrastructureWaitTicks() { return 0; }
}
