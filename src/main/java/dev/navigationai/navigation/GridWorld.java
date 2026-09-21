package dev.navigationai.navigation;

public interface GridWorld {
    boolean passable(GridPos pos);

    /** Returns whether the body can move directly between the two cells. */
    default boolean canTraverse(GridPos from, GridPos to) {
        if (!passable(to)) return false;
        int dx = Math.abs(to.x() - from.x());
        int dz = Math.abs(to.z() - from.z());
        // Do not let diagonal movement cut through two touching corners.
        if (to.y() == from.y() && dx == 1 && dz == 1) {
            return passable(from.add(to.x() - from.x(), 0, 0))
                    && passable(from.add(0, 0, to.z() - from.z()));
        }
        return true;
    }

    default boolean dangerous(GridPos pos) { return false; }

    /** Lower-bound movement cost used by A*. */
    default double cost(GridPos from, GridPos to) {
        double horizontal = Math.hypot(to.x() - from.x(), to.z() - from.z());
        int vertical = Math.abs(to.y() - from.y());
        return horizontal + vertical * 1.45;
    }
}
