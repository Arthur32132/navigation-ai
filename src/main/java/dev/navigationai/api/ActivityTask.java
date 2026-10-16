package dev.navigationai.api;

import java.util.concurrent.CompletableFuture;

public interface ActivityTask {
    ActivityStatus status();
    int stepIndex();
    CompletableFuture<ActivityResult> completion();
    void cancel();
}
