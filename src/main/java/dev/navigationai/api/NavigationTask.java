package dev.navigationai.api;

import java.util.concurrent.CompletableFuture;
import java.util.Optional;

public interface NavigationTask {
    NavigationStatus status();
    CompletableFuture<NavigationResult> completion();
    void cancel();
    default NavigationDiagnostics diagnostics() { return NavigationDiagnostics.unavailable(); }
    default Optional<NavigationFailureContext> failureContext() { return Optional.empty(); }
}
