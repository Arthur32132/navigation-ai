package dev.navigationai.api;

import java.util.Optional;

public interface Navigator {
    NavigationTask goTo(NavigationGoal goal, NavigationPolicy policy);
    NavigationTask follow(NavigationGoal.FollowGoal goal, NavigationPolicy policy);
    void cancel();
    NavigationStatus status();
    Optional<NavigationGoal> goal();
    /** Placements awaiting a safe rollback; inspect after cancellation or failure. */
    default int pendingRollbackCount() { return 0; }
}
