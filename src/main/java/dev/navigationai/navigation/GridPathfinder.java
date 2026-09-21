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
        {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
        {1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {0, -1, -1}
    };

    private GridPathfinder() { }

    public static GridPath find(GridWorld world, GridPos start, GridPos goal, NavigationPolicy policy) {
        if (!world.passable(start)) return new GridPath(List.of(), 0);
        if (start.equals(goal)) return new GridPath(List.of(start), 1);
        record Entry(GridPos pos, double priority) { }
        PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::priority));
        Map<GridPos, Double> score = new HashMap<>();
        Map<GridPos, GridPos> parent = new HashMap<>();
        Set<GridPos> closed = new HashSet<>();
        score.put(start, 0.0);
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
                if (!world.passable(next) || closed.contains(next)) continue;
                double nextScore = score.get(current) + world.cost(current, next);
                if (nextScore >= score.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                score.put(next, nextScore);
                parent.put(next, current);
                open.add(new Entry(next, nextScore + heuristic(next, goal)));
            }
        }
        return new GridPath(List.of(), visited);
    }

    private static double heuristic(GridPos a, GridPos b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()) + Math.abs(a.y() - b.y()) * 1.25;
    }

    private static List<GridPos> reconstruct(Map<GridPos, GridPos> parent, GridPos current) {
        ArrayList<GridPos> result = new ArrayList<>();
        result.add(current);
        while (parent.containsKey(current)) { current = parent.get(current); result.add(current); }
        Collections.reverse(result);
        return result;
    }
}
