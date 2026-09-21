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
    private record SearchNode(GridPos pos, int waterSteps) { }

    private static final int[][] MOVES = {
        {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
        {0, 1, 0}, {0, -1, 0},
        {1, 1, 0}, {-1, 1, 0}, {0, 1, 1}, {0, 1, -1},
        {1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {0, -1, -1},
        {2, 0, 0}, {-2, 0, 0}, {3, 0, 0}, {-3, 0, 0}, {4, 0, 0}, {-4, 0, 0},
        {0, 0, 2}, {0, 0, -2}, {0, 0, 3}, {0, 0, -3}, {0, 0, 4}, {0, 0, -4}
    };

    private GridPathfinder() { }

    public static GridPath find(GridWorld world, GridPos start, GridPos goal, NavigationPolicy policy) {
        return find(world, start, goal, policy, NodeBudget.unlimited());
    }

    public static GridPath find(GridWorld world, GridPos start, GridPos goal,
                                NavigationPolicy policy, NodeBudget budget) {
        if (!world.passable(start)) return new GridPath(List.of(), 0);
        if (start.equals(goal)) return new GridPath(List.of(start), 1);
        record Entry(SearchNode node, double priority) { }
        PriorityQueue<Entry> open = new PriorityQueue<>(Comparator
                .comparingDouble(Entry::priority)
                .thenComparingInt(entry -> entry.node().pos().y())
                .thenComparingInt(entry -> entry.node().pos().x())
                .thenComparingInt(entry -> entry.node().pos().z())
                .thenComparingInt(entry -> entry.node().waterSteps()));
        Map<SearchNode, Double> score = new HashMap<>();
        Map<SearchNode, SearchNode> parent = new HashMap<>();
        Map<SearchNode, Integer> steps = new HashMap<>();
        Set<SearchNode> closed = new HashSet<>();
        int startWaterSteps = world.isWater(start) ? 1 : 0;
        if (startWaterSteps > world.maxWaterSteps()) return new GridPath(List.of(), 0);
        SearchNode startNode = new SearchNode(start, startWaterSteps);
        score.put(startNode, 0.0);
        steps.put(startNode, 0);
        open.add(new Entry(startNode, heuristic(start, goal)));
        int visited = 0;
        while (!open.isEmpty() && visited < policy.maxSearchNodes()) {
            if (Thread.currentThread().isInterrupted()) return new GridPath(List.of(), visited);
            if (!budget.tryConsume()) return new GridPath(List.of(), visited, true);
            SearchNode currentNode = open.poll().node();
            GridPos current = currentNode.pos();
            if (!closed.add(currentNode)) continue;
            visited++;
            if (current.equals(goal)) {
                return new GridPath(reconstruct(parent, currentNode), visited);
            }
            for (int[] move : MOVES) {
                GridPos next = current.add(move[0], move[1], move[2]);
                boolean jump = Math.max(Math.abs(move[0]), Math.abs(move[2])) > 1;
                if (jump
                        ? !world.passable(next) || !world.canJump(current, next)
                        : !world.canTraverse(current, next)) continue;
                int nextWaterSteps = currentNode.waterSteps() + (world.isWater(next) ? 1 : 0);
                if (nextWaterSteps > world.maxWaterSteps()) continue;
                SearchNode nextNode = new SearchNode(next, nextWaterSteps);
                if (closed.contains(nextNode)) continue;
                int nextSteps = steps.get(currentNode) + 1;
                if (nextSteps > policy.maxPathLength()) continue;
                double nextScore = score.get(currentNode) + world.cost(current, next);
                if (world.dangerous(next)) nextScore += policy.dangerCost();
                if (nextScore >= score.getOrDefault(nextNode, Double.POSITIVE_INFINITY)) continue;
                score.put(nextNode, nextScore);
                steps.put(nextNode, nextSteps);
                parent.put(nextNode, currentNode);
                open.add(new Entry(nextNode, nextScore + heuristic(next, goal)));
            }
        }
        return new GridPath(List.of(), visited);
    }

    private static double heuristic(GridPos a, GridPos b) {
        return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z()))
                + Math.abs(a.y() - b.y());
    }

    private static List<GridPos> reconstruct(Map<SearchNode, SearchNode> parent, SearchNode current) {
        ArrayList<GridPos> result = new ArrayList<>();
        GridPos position = current.pos();
        result.add(position);
        while (parent.containsKey(current)) {
            current = parent.get(current);
            result.add(current.pos());
        }
        Collections.reverse(result);
        return result;
    }
}
