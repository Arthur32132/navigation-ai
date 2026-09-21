package dev.navigationai.navigation;

public interface GridWorld {
    boolean passable(GridPos pos);
    default double cost(GridPos from, GridPos to) { return from.y() == to.y() ? 1.0 : 1.35; }
}
