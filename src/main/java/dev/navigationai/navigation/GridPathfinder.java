package dev.navigationai.navigation;

import dev.navigationai.api.NavigationPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Deterministic, bounded A* over a snapshot. It never touches Minecraft state. */
public final class GridPathfinder {
    private record State(GridPos pos, int waterSteps, int placements) { }
    private static final class SearchNode {
        final State state;
        final int steps;
        final double cost;
        final SearchNode parent;
        final GridTransition transition;
        boolean superseded;

        SearchNode(State state, int steps, double cost, SearchNode parent, GridTransition transition) {
            this.state = state;
            this.steps = steps;
            this.cost = cost;
            this.parent = parent;
            this.transition = transition;
        }
        GridPos pos() { return state.pos(); }
        int waterSteps() { return state.waterSteps(); }
    }

    /**
     * The longest safe sprint jump covers four horizontal blocks: a three
     * block gap plus the landing cell.  Generate every direction inside that
     * radius, rather than only the two cardinal axes, so A* can use a valid
     * diagonal trajectory around a corner or obstacle.
     */
    private static final int[][] MOVES = createMoves();
    private static final int[][] DROP_MOVES = createDropMoves();

    private GridPathfinder() { }

    public static GridPath find(GridWorld world, GridPos start, GridPos goal, NavigationPolicy policy) {
        return find(world, start, goal, policy, NodeBudget.unlimited());
    }

    public static GridPath find(GridWorld world, GridPos start, GridPos goal,
                                NavigationPolicy policy, NodeBudget budget) {
        return find(world, start, goal, policy, budget, false);
    }

    /**
     * Searches a bounded world and, when requested, returns the best useful
     * frontier path if the goal is outside the known area or the local budget
     * ends first.  The default API keeps its historical all-or-nothing
     * behaviour for callers that do not execute incremental plans.
     */
    public static GridPath find(GridWorld world, GridPos start, GridPos goal,
                                NavigationPolicy policy, NodeBudget budget,
                                boolean returnBestPartial) {
        return findToAny(world, start, Set.of(goal), goal, policy, budget, returnBestPartial);
    }

    /** Searches all acceptable endpoints together instead of choosing an unverified neighbour. */
    public static GridPath findToAny(GridWorld world, GridPos start, Set<GridPos> goals, GridPos frontierTarget,
                                    NavigationPolicy policy, NodeBudget budget, boolean returnBestPartial) {
        return findToAny(world, start, goals, frontierTarget, policy, budget, returnBestPartial, Set.of());
    }

    /** Excludes previously used frontier cells while retaining them as ordinary route cells. */
    public static GridPath findToAny(GridWorld world, GridPos start, Set<GridPos> goals, GridPos frontierTarget,
                                    NavigationPolicy policy, NodeBudget budget, boolean returnBestPartial,
                                    Set<GridPos> exploredFrontiers) {
        return search(world, start, goals, frontierTarget, policy, budget, returnBestPartial, exploredFrontiers, null);
    }

    public static GridExploration explore(GridWorld world, GridPos start, Set<GridPos> goals, GridPos frontierTarget,
                                           NavigationPolicy policy, NodeBudget budget, Set<GridPos> exploredFrontiers) {
        List<GridExploration.Portal> exits = new ArrayList<>();
        GridPath path = search(world, start, goals, frontierTarget, policy, budget, true, exploredFrontiers, exits);
        return new GridExploration(path, exits);
    }

    private static GridPath search(GridWorld world, GridPos start, Set<GridPos> goals, GridPos frontierTarget,
                                    NavigationPolicy policy, NodeBudget budget, boolean returnBestPartial,
                                    Set<GridPos> exploredFrontiers, List<GridExploration.Portal> exits) {
        if (!world.passable(start)) return new GridPath(List.of(), 0);
        goals = Set.copyOf(goals);
        if (goals.contains(start)) return GridPath.reached(List.of(start), 1);
        record Entry(SearchNode node, double priority) { }
        PriorityQueue<Entry> open = new PriorityQueue<>(Comparator
                .comparingDouble(Entry::priority)
                .thenComparingInt(entry -> entry.node().pos().y())
                .thenComparingInt(entry -> entry.node().pos().x())
                .thenComparingInt(entry -> entry.node().pos().z())
                .thenComparingInt(entry -> entry.node().waterSteps())
                .thenComparingInt(entry -> entry.node().state.placements()));
        Map<State, List<SearchNode>> labels = new HashMap<>();
        int waterLimit = world.maxWaterSteps();
        int placementLimit = world.maxPlacements();
        // Unlimited swimming needs no extra A* state dimension. Counting
        // every water cell there would revisit the same position with many
        // different counters and exhaust the node budget.
        int startWaterSteps = waterLimit == Integer.MAX_VALUE ? 0 : world.isWater(start) ? 1 : 0;
        if (startWaterSteps > waterLimit) return new GridPath(List.of(), 0);
        SearchNode startNode = new SearchNode(new State(start, startWaterSteps, 0), 0, 0.0, null, null);
        labels.put(startNode.state, new ArrayList<>(List.of(startNode)));
        open.add(new Entry(startNode, goalHeuristic(start, goals, frontierTarget)));
        int visited = 0;
        SearchNode bestNode = startNode;
        SearchNode bestFrontier = null;
        double bestFrontierScore = heuristic(start, frontierTarget);
        double bestPathCost = 0.0D;
        boolean budgetExhausted = false;
        boolean searchLimitReached = false;
        boolean pathLengthReached = false;
        boolean goalVisible = goals.stream().anyMatch(world::passable);
        Map<GridPos, SearchNode> portals = exits == null ? null : new java.util.LinkedHashMap<>();
        while (!open.isEmpty() && visited < policy.maxSearchNodes()) {
            if (Thread.currentThread().isInterrupted()) return new GridPath(List.of(), visited);
            SearchNode currentNode = open.poll().node();
            if (currentNode.superseded) continue;
            if (!budget.tryConsume()) {
                budgetExhausted = true;
                break;
            }
            GridPos current = currentNode.pos();
            visited++;
            double currentCost = currentNode.cost;
            if (portals != null && currentNode != startNode && world.isFrontier(current)) {
                // Eight-block portal regions keep a large cliff or cave boundary bounded.
                GridPos region = new GridPos(Math.floorDiv(current.x(), 8), Math.floorDiv(current.y(), 8), Math.floorDiv(current.z(), 8));
                SearchNode previous = portals.get(region);
                if (previous != null && currentCost < previous.cost || previous == null && portals.size() < 64) portals.put(region, currentNode);
            }
            if (currentNode != startNode && world.isFrontier(current) && !exploredFrontiers.contains(current)) {
                if (bestFrontier == null || preferFrontier(currentNode, bestFrontier, start, frontierTarget)) {
                    bestFrontier = currentNode;
                }
            }
            double frontierScore = frontierScore(current, frontierTarget, currentCost);
            if (frontierScore < bestFrontierScore
                    || (frontierScore == bestFrontierScore && currentCost < bestPathCost)) {
                bestNode = currentNode;
                bestFrontierScore = frontierScore;
                bestPathCost = currentCost;
            }
            if (goals.contains(current)) {
                return new GridPath(reconstruct(currentNode), visited, false, true, false, reconstructTransitions(currentNode));
            }
            for (int[] move : MOVES) {
                GridPos next = current.add(move[0], move[1], move[2]);
                boolean jump = Math.max(Math.abs(move[0]), Math.abs(move[2])) > 1;
                GridTransition transition = TransitionRules.describe(world, current, next, policy);
                if (transition == null) continue;
                int nextWaterSteps = waterLimit == Integer.MAX_VALUE ? 0
                        : currentNode.waterSteps() + (world.isWater(next) ? 1 : 0);
                if (nextWaterSteps > waterLimit) continue;
                int nextPlacements = placementLimit == Integer.MAX_VALUE ? 0
                        : currentNode.state.placements() + (world.requiresPlacement(next) ? 1 : 0);
                if (nextPlacements > placementLimit) continue;
                int nextSteps = currentNode.steps + 1;
                if (nextSteps > policy.maxPathLength()) {
                    pathLengthReached = true;
                    continue;
                }
                // A jump is a fallback for a gap or a raised transition.  On
                // flat ground, walking the intermediate cells is safer and
                // gives the executor more control over the arrival point.
                // Keep the jump available, but make it slightly more
                // expensive so an equivalent walk wins the tie.
                double nextScore = currentNode.cost + world.cost(current, next)
                        + (jump ? 0.35D : 0.0D);
                if (world.dangerous(next)) nextScore += policy.dangerCost();
                SearchNode nextNode = new SearchNode(new State(next, nextWaterSteps, nextPlacements), nextSteps, nextScore, currentNode, transition);
                if (retainLabel(labels, nextNode)) {
                    open.add(new Entry(nextNode, nextScore + goalHeuristic(next, goals, frontierTarget)));
                }
            }
            if (policy.maxFallDistance() >= 2) {
                for (int[] move : DROP_MOVES) {
                    int dropDistance = -move[1];
                    if (dropDistance > policy.maxFallDistance()) continue;
                    GridPos next = current.add(move[0], move[1], move[2]);
                    GridTransition transition = TransitionRules.describe(world, current, next, policy);
                    if (transition == null) continue;
                    int nextWaterSteps = waterLimit == Integer.MAX_VALUE ? 0
                            : currentNode.waterSteps() + (world.isWater(next) ? 1 : 0);
                    if (nextWaterSteps > waterLimit) continue;
                    int nextPlacements = placementLimit == Integer.MAX_VALUE ? 0
                            : currentNode.state.placements() + (world.requiresPlacement(next) ? 1 : 0);
                    if (nextPlacements > placementLimit) continue;
                    int nextSteps = currentNode.steps + 1;
                    if (nextSteps > policy.maxPathLength()) {
                        pathLengthReached = true;
                        continue;
                    }
                    double nextScore = currentNode.cost + world.cost(current, next)
                            + dropDistance * 0.35D;
                    if (world.dangerous(next)) nextScore += policy.dangerCost();
                    SearchNode nextNode = new SearchNode(new State(next, nextWaterSteps, nextPlacements), nextSteps, nextScore, currentNode, transition);
                    if (retainLabel(labels, nextNode)) {
                        open.add(new Entry(nextNode, nextScore + goalHeuristic(next, goals, frontierTarget)));
                    }
                }
            }
        }
        if (visited >= policy.maxSearchNodes() && !open.isEmpty()) searchLimitReached = true;
        if (exits != null) {
            for (SearchNode exit : portals.values()) {
                List<GridPos> route = reconstruct(exit);
                if (route.size() > 256) continue;
                boolean reverse = true;
                for (int i = route.size()-1; i > 0; i--) {
                    if (TransitionRules.describe(world, route.get(i), route.get(i-1), policy) == null) { reverse = false; break; }
                }
                exits.add(new GridExploration.Portal(route, reverse));
            }
        }
        SearchNode partial = !goalVisible ? bestFrontier : bestNode;
        if (returnBestPartial && partial != null && partial != startNode
                && (budgetExhausted || searchLimitReached || pathLengthReached || !goalVisible)) {
            return new GridPath(reconstruct(partial), visited, budgetExhausted, false, true, reconstructTransitions(partial));
        }
        return new GridPath(List.of(), visited, budgetExhausted);
    }

    private static boolean retainLabel(Map<State, List<SearchNode>> labels, SearchNode candidate) {
        List<SearchNode> alternatives = labels.computeIfAbsent(candidate.state, ignored -> new ArrayList<>());
        for (SearchNode previous : alternatives) {
            if (previous.cost <= candidate.cost && previous.steps <= candidate.steps) return false;
        }
        alternatives.removeIf(previous -> {
            boolean dominated = candidate.cost <= previous.cost && candidate.steps <= previous.steps;
            if (dominated) previous.superseded = true;
            return dominated;
        });
        alternatives.add(candidate);
        return true;
    }

    private static double goalHeuristic(GridPos position, Set<GridPos> goals, GridPos frontierTarget) {
        if (goals.isEmpty()) return heuristic(position, frontierTarget);
        double nearest = Double.POSITIVE_INFINITY;
        for (GridPos goal : goals) nearest = Math.min(nearest, heuristic(position, goal));
        return nearest;
    }

    private static double frontierScore(GridPos position, GridPos goal, double pathCost) {
        // Distance to the real goal dominates, while a small path-cost term
        // avoids selecting a very expensive frontier when two nodes are close.
        return heuristic(position, goal) + pathCost * 0.05D;
    }

    private static boolean preferFrontier(SearchNode candidate, SearchNode previous, GridPos start, GridPos target) {
        double startDistance = heuristic(start, target);
        double candidateDistance = heuristic(candidate.pos(), target);
        double previousDistance = heuristic(previous.pos(), target);
        boolean candidateImproves = candidateDistance < startDistance;
        boolean previousImproves = previousDistance < startDistance;
        if (candidateImproves != previousImproves) return candidateImproves;
        if (candidateImproves) {
            if (candidateDistance != previousDistance) return candidateDistance < previousDistance;
        } else {
            double candidateTravel = heuristic(start, candidate.pos());
            double previousTravel = heuristic(start, previous.pos());
            if (candidateTravel != previousTravel) return candidateTravel > previousTravel;
        }
        return candidate.cost < previous.cost;
    }

    private static double heuristic(GridPos a, GridPos b) {
        return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z()))
                + Math.abs(a.y() - b.y());
    }

    private static int[][] createMoves() {
        ArrayList<int[]> moves = new ArrayList<>();
        moves.add(new int[] {1, 0, 0}); moves.add(new int[] {-1, 0, 0});
        moves.add(new int[] {0, 0, 1}); moves.add(new int[] {0, 0, -1});
        moves.add(new int[] {1, 0, 1}); moves.add(new int[] {1, 0, -1});
        moves.add(new int[] {-1, 0, 1}); moves.add(new int[] {-1, 0, -1});
        moves.add(new int[] {0, 1, 0}); moves.add(new int[] {0, -1, 0});
        moves.add(new int[] {1, 1, 0}); moves.add(new int[] {-1, 1, 0});
        moves.add(new int[] {0, 1, 1}); moves.add(new int[] {0, 1, -1});
        moves.add(new int[] {1, -1, 0}); moves.add(new int[] {-1, -1, 0});
        moves.add(new int[] {0, -1, 1}); moves.add(new int[] {0, -1, -1});

        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            if (dx == 0 && dz == 0) continue;
            double distance = Math.hypot(dx, dz);
            if (distance >= 2.0D && distance <= 4.0D) {
                moves.add(new int[] {dx, 0, dz});
            }
        }
        return moves.toArray(int[][]::new);
    }

    private static int[][] createDropMoves() {
        ArrayList<int[]> moves = new ArrayList<>();
        for (int distance = 2; distance <= 16; distance++) {
            moves.add(new int[] {1, -distance, 0});
            moves.add(new int[] {-1, -distance, 0});
            moves.add(new int[] {0, -distance, 1});
            moves.add(new int[] {0, -distance, -1});
        }
        return moves.toArray(int[][]::new);
    }

    private static List<GridPos> reconstruct(SearchNode current) {
        ArrayList<GridPos> result = new ArrayList<>();
        GridPos position = current.pos();
        result.add(position);
        while (current.parent != null) {
            current = current.parent;
            result.add(current.pos());
        }
        Collections.reverse(result);
        return result;
    }

    private static List<GridTransition> reconstructTransitions(SearchNode current) {
        ArrayList<GridTransition> result = new ArrayList<>();
        while (current.parent != null) {
            result.add(current.transition);
            current = current.parent;
        }
        Collections.reverse(result);
        return result;
    }
}
