package dev.navigationai.navigation;

import dev.navigationai.api.NavigationPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Deterministic, bounded A* over a snapshot. It never touches Minecraft state. */
public final class GridPathfinder {
    private static final int[][] MOVES = {
        {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
        {0, 1, 0}, {0, -1, 0},
        {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
        {1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {0, -1, -1}
    };

    private GridPathfinder() { }

    public static GridPath find(GridWorld world, GridPos start, GridPos goal, NavigationPolicy policy) {
        if (!world.passable(start)) return new GridPath(List.of(), 0);
        if (start.equals(goal)) return new GridPath(List.of(start), 1);
        record Entry(GridPos pos, double priority) { }
        PriorityQueue<Entry> open = new PriorityQueue<>(Comparator
                .comparingDouble(Entry::priority)
                .thenComparingInt(entry -> entry.pos().y())
                .thenComparingInt(entry -> entry.pos().x())
                .thenComparingInt(entry -> entry.pos().z()));
        Map<GridPos, Double> score = new HashMap<>();
        Map<GridPos, GridPos> parent = new HashMap<>();
        Map<GridPos, Integer> steps = new HashMap<>();
        Set<GridPos> closed = new HashSet<>();
        score.put(start, 0.0);
        steps.put(start, 0);
        open.add(new Entry(start, heuristic(start, goal)));
        int visited = 0;
        while (!open.isEmpty() && visited < policy.maxSearchNodes()) {
            GridPos current = open.poll().pos();
            if (!closed.add(current)) continue;
            visited++;
            if (current.equals(goal)) {
                return new GridPath(reconstruct(parent, current), visited);
            }
            for (int[] move : MOVES) {
                GridPos next = current.add(move[0], move[1], move[2]);
                if (!world.canTraverse(current, next) || closed.contains(next)) continue;
                int nextSteps = steps.get(current) + 1;
                if (nextSteps > policy.maxPathLength()) continue;
                double nextScore = score.get(current) + world.cost(current, next);
                if (world.dangerous(next)) nextScore += policy.dangerCost();
                if (nextScore >= score.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                score.put(next, nextScore);
                steps.put(next, nextSteps);
                parent.put(next, current);
                open.add(new Entry(next, nextScore + heuristic(next, goal)));
            }
        }
        return new GridPath(List.of(), visited);
    }

    private static double heuristic(GridPos a, GridPos b) {
        return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z()))
                + Math.abs(a.y() - b.y());
    }

    private static List<GridPos> reconstruct(Map<GridPos, GridPos> parent, GridPos current) {
        ArrayList<GridPos> result = new ArrayList<>();
        result.add(current);
        while (parent.containsKey(current)) { current = parent.get(current); result.add(current); }
        Collections.reverse(result);
        return result;
    }
}
