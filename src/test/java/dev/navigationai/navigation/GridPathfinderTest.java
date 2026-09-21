package dev.navigationai.navigation;

import dev.navigationai.api.NavigationPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GridPathfinderTest {
    private static NavigationPolicy policy(int nodes) {
        return new NavigationPolicy(false, false, false, false, nodes, 512, 8, 0);
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
}
