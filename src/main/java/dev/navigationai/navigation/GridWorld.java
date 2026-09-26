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

    /** Returns whether the bot can jump directly from one cell to another. */
    default boolean canJump(GridPos from, GridPos to) { return false; }

    /** Returns whether the bot can safely step off a ledge and land at {@code to}. */
    default boolean canDrop(GridPos from, GridPos to) { return false; }

    /** Returns whether the bot must keep sneak held while occupying this cell. */
    default boolean requiresSneak(GridPos pos) { return false; }

    /** Returns whether a cell contains water that requires swimming/wading. */
    default boolean isWater(GridPos pos) { return false; }

    /** A ladder or swimming descent is not a fall and can ignore the fall limit. */
    default boolean canDescendWithoutFalling(GridPos from, GridPos to) { return false; }
    /** Whether entering this cell consumes one support block. */
    default boolean requiresPlacement(GridPos pos) { return false; }
    /** Number of support blocks available for one local route. */
    default int maxPlacements() { return Integer.MAX_VALUE; }

    /** Maximum number of water cells a route may use. */
    default int maxWaterSteps() { return Integer.MAX_VALUE; }

    default boolean dangerous(GridPos pos) { return false; }

    /** Lower-bound movement cost used by A*. */
    default double cost(GridPos from, GridPos to) {
        double horizontal = Math.hypot(to.x() - from.x(), to.z() - from.z());
        int vertical = Math.abs(to.y() - from.y());
        return horizontal + vertical * 1.45;
    }
}
