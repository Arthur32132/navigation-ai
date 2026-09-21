package dev.navigationai.api;

import java.util.concurrent.CompletableFuture;

public interface MiningTask {
    MiningStatus status();
    CompletableFuture<MiningResult> completion();
    void cancel();
}
