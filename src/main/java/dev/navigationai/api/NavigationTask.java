package dev.navigationai.api;

import java.util.concurrent.CompletableFuture;
import java.util.Optional;

public interface NavigationTask {
    NavigationStatus status();
    CompletableFuture<NavigationResult> completion();
    void cancel();
    default boolean waitingForInfrastructure() { return status()==NavigationStatus.COMPUTING; }
    default long infrastructureWaitTicks() { return 0; }
    default NavigationDiagnostics diagnostics() { return NavigationDiagnostics.unavailable(); }
    default Optional<NavigationFailureContext> failureContext() { return Optional.empty(); }
}
