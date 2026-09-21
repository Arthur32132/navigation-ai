package dev.navigationai.navigation;

/** Cooperative budget consulted before expanding each A* node. */
@FunctionalInterface
public interface NodeBudget {
    boolean tryConsume();

    static NodeBudget unlimited() { return () -> true; }
}
