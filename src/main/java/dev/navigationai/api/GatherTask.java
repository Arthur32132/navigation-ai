package dev.navigationai.api;

import java.util.concurrent.CompletableFuture;

public interface GatherTask {
    GatherStatus status();
    GatherPhase phase();
    int collectedItems();
    CompletableFuture<GatherResult> completion();
    void cancel();
}
