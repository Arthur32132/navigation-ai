package dev.navigationai.navigation;

import java.util.List;

/**
 * Result of one bounded search.
 *
 * A path can be useful even when the goal is outside the captured world or
 * when the local search limit was reached.  Such a path is marked partial so
 * the runtime can execute it and continue planning from the new position.
 */
public record GridPath(
        List<GridPos> nodes,
        int nodesVisited,
        boolean budgetExhausted,
        boolean goalReached,
        boolean partial,
        List<GridTransition> transitions) {

    public GridPath(List<GridPos> nodes, int nodesVisited, boolean budgetExhausted,
                    boolean goalReached, boolean partial) {
        this(nodes, nodesVisited, budgetExhausted, goalReached, partial, List.of());
    }

    /** Backwards-compatible constructor for a complete path. */
    public GridPath(List<GridPos> nodes, int nodesVisited) {
        this(nodes, nodesVisited, false, !nodes.isEmpty(), false);
    }

    /** Backwards-compatible constructor used for an empty budget result. */
    public GridPath(List<GridPos> nodes, int nodesVisited, boolean budgetExhausted) {
        this(nodes, nodesVisited, budgetExhausted,
                !nodes.isEmpty() && !budgetExhausted, false);
    }

    public GridPath {
        nodes = List.copyOf(nodes);
        transitions = List.copyOf(transitions);
        if (!transitions.isEmpty()) {
            if (transitions.size() != nodes.size() - 1) throw new IllegalArgumentException("Missing transitions");
            for (int i = 0; i < transitions.size(); i++) {
                if (!transitions.get(i).from().equals(nodes.get(i))
                        || !transitions.get(i).to().equals(nodes.get(i + 1))) {
                    throw new IllegalArgumentException("Transition endpoints differ from path nodes");
                }
            }
        }
        if (goalReached && nodes.isEmpty()) {
            throw new IllegalArgumentException("A reached path must contain at least one node");
        }
        if (goalReached && partial) {
            throw new IllegalArgumentException("A reached path cannot be partial");
        }
    }

    public static GridPath reached(List<GridPos> nodes, int nodesVisited) {
        return new GridPath(nodes, nodesVisited, false, true, false);
    }

    public static GridPath incomplete(List<GridPos> nodes, int nodesVisited, boolean budgetExhausted) {
        return new GridPath(nodes, nodesVisited, budgetExhausted, false, true);
    }
}
