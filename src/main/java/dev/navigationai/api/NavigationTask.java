package dev.navigationai.api;

import java.util.concurrent.CompletableFuture;

public interface NavigationTask {
    NavigationStatus status();
    CompletableFuture<NavigationResult> completion();
    void cancel();
}
