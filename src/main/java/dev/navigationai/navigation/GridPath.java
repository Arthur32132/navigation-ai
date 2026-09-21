package dev.navigationai.navigation;

import java.util.List;

public record GridPath(List<GridPos> nodes, int nodesVisited, boolean budgetExhausted) {
    public GridPath(List<GridPos> nodes, int nodesVisited) {
        this(nodes, nodesVisited, false);
    }

    public GridPath { nodes = List.copyOf(nodes); }
}
