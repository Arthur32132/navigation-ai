package dev.navigationai.navigation;

import dev.navigationai.api.NavigationPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GridPathfinderTest {
    private static NavigationPolicy policy(int nodes) {
        return new NavigationPolicy(false, false, false, false, nodes, 512, 8, 0);
    }

    private static NavigationPolicy policy(int nodes, int maxPathLength, double dangerCost) {
        return new NavigationPolicy(false, false, false, false, nodes, maxPathLength, 8, dangerCost);
    }

    @Test
    void routesAroundAnObstacle() {
        GridWorld world = pos -> pos.y() == 0 && pos.x() >= -2 && pos.x() <= 8 && pos.z() >= -2 && pos.z() <= 8
                && !(pos.x() == 2 && pos.z() >= 0 && pos.z() <= 3);
        GridPos start = new GridPos(0, 0, 0);
        GridPos goal = new GridPos(5, 0, 3);
        GridPath path = GridPathfinder.find(world, start, goal, policy(10_000));
        assertFalse(path.nodes().isEmpty());
        assertEquals(start, path.nodes().getFirst());
        assertEquals(goal, path.nodes().getLast());
        assertTrue(path.nodes().stream().noneMatch(p -> p.x() == 2 && p.z() >= 0 && p.z() <= 3));
    }

    @Test
    void honorsSearchBudget() {
        GridWorld world = pos -> Math.abs(pos.x()) < 100 && Math.abs(pos.z()) < 100 && pos.y() == 0;
        GridPath path = GridPathfinder.find(world, new GridPos(0, 0, 0), new GridPos(90, 0, 90), policy(128));
        assertTrue(path.nodes().isEmpty());
        assertEquals(128, path.nodesVisited());
    }

    @Test
    void allowsDiagonalVerticalStepWhenCenterCellIsBlocked() {
        GridPos start = new GridPos(0, 0, 0);
        GridPos goal = new GridPos(1, 1, 0);
        GridWorld world = pos -> pos.equals(start) || pos.equals(goal);

        GridPath path = GridPathfinder.find(world, start, goal, policy(128));

        assertEquals(List.of(start, goal), path.nodes());
    }

    @Test
    void rejectsDiagonalCornerCutting() {
        GridPos start = new GridPos(0, 0, 0);
        GridPos goal = new GridPos(1, 0, 1);
        GridWorld world = pos -> pos.equals(start) || pos.equals(goal);

        GridPath path = GridPathfinder.find(world, start, goal, policy(128));

        assertTrue(path.nodes().isEmpty());
    }

    @Test
    void honorsMaximumPathLength() {
        GridWorld world = pos -> pos.y() == 0 && pos.x() >= 0 && pos.x() <= 8 && pos.z() == 0;
        GridPath path = GridPathfinder.find(world, new GridPos(0, 0, 0), new GridPos(4, 0, 0), policy(128, 3, 0));

        assertTrue(path.nodes().isEmpty());
    }

    @Test
    void dangerCostPrefersLongerSafeRoute() {
        GridWorld world = new GridWorld() {
            @Override public boolean passable(GridPos pos) {
                return pos.y() == 0 && pos.x() >= 0 && pos.x() <= 2 && Math.abs(pos.z()) <= 1;
            }
            @Override public boolean dangerous(GridPos pos) { return pos.equals(new GridPos(1, 0, 0)); }
        };
        GridPath path = GridPathfinder.find(world, new GridPos(0, 0, 0), new GridPos(2, 0, 0), policy(256, 32, 100));

        assertFalse(path.nodes().isEmpty());
        assertTrue(path.nodes().stream().noneMatch(p -> p.equals(new GridPos(1, 0, 0))));
    }

    @Test
    void supportsPureVerticalClimbMoves() {
        GridPos start = new GridPos(0, 0, 0);
        GridPos goal = new GridPos(0, 3, 0);
        GridWorld world = pos -> pos.x() == 0 && pos.z() == 0 && pos.y() >= 0 && pos.y() <= 3;

        GridPath path = GridPathfinder.find(world, start, goal, policy(128));

        assertEquals(List.of(start, new GridPos(0, 1, 0), new GridPos(0, 2, 0), goal), path.nodes());
    }
}
