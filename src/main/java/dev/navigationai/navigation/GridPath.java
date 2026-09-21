package dev.navigationai.navigation;

import java.util.List;

public record GridPath(List<GridPos> nodes, int nodesVisited) {
    public GridPath { nodes = List.copyOf(nodes); }
}
