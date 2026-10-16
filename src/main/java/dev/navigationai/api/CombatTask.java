package dev.navigationai.api;

import java.util.concurrent.CompletableFuture;

public interface CombatTask {
    CombatStatus status();
    CompletableFuture<CombatResult> completion();
    void cancel();
    default CombatDiagnostics diagnostics() { return CombatDiagnostics.idle(); }
}
