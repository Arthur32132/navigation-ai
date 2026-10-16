package dev.navigationai.navigation;

import java.util.*;
import java.util.function.Predicate;

/** Bounded directed connectivity of observed portal routes, with revision invalidation. */
public final class RegionRouteGraph {
    private record Edge(GridPos from, GridPos to, List<GridPos> route, Object revision) { }
    private final LinkedHashMap<List<GridPos>, Edge> edges = new LinkedHashMap<>();
    private final Set<GridPos> rejected = new HashSet<>();
    private final Set<Object> expired = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final int MAX_EDGES = 256;

    public void observe(List<GridExploration.Portal> portals, Object revision) {
        for (var portal : portals) {
            List<GridPos> route = portal.route();
            if (route.size() < 2) continue;
            add(route, revision);
            if (portal.reversible()) {
                var reverse = new ArrayList<>(route); Collections.reverse(reverse); add(List.copyOf(reverse), revision);
            }
        }
        while (edges.size() > MAX_EDGES) edges.remove(edges.keySet().iterator().next());
        Set<Object> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        for(Edge edge : edges.values()) retained.add(edge.revision);
        expired.removeIf(stamp -> !retained.contains(stamp));
    }

    private void add(List<GridPos> route, Object revision) {
        List<GridPos> key = List.of(route.getFirst(), route.getLast());
        edges.remove(key);
        edges.put(key, new Edge(route.getFirst(), route.getLast(), route, revision));
    }

    public void reject(GridPos waypoint) { rejected.add(waypoint); }
    public void clear() { edges.clear(); rejected.clear(); expired.clear(); }
    public int edgeCount() { return edges.size(); }
    public int unverifiedEdgeCount() { return (int)edges.values().stream().filter(edge -> expired.contains(edge.revision)).count(); }

    /** Select an untried exit reachable through known regions, even behind the current dead end. */
    public Optional<GridPos> waypoint(GridPos start, GridPos goal, Set<GridPos> explored, Predicate<Object> current) {
        var revisions = new IdentityHashMap<Object, Boolean>();
        // Unload/reload and unrelated changes invalidate geometric verification.
        // Keep bounded topology as a steering hint: the caller MUST build a new
        // local path to every waypoint. No stored route is ever executed directly.
        for(Edge edge : edges.values()) if(!revisions.computeIfAbsent(edge.revision,current::test)) expired.add(edge.revision);
        Map<GridPos,List<Edge>> outgoing = new HashMap<>();
        Set<GridPos> anchors = new HashSet<>(); anchors.add(start);
        for(Edge edge : edges.values()) { anchors.add(edge.from); anchors.add(edge.to); }
        for (Edge edge : edges.values()) {
            outgoing.computeIfAbsent(edge.from, ignored -> new ArrayList<>()).add(edge);
            // Background search anchors usually lie inside a previous route,
            // rather than exactly at its portal. Join all such overlaps.
            for(int index=1;index<edge.route.size()-1;index++) if(anchors.contains(edge.route.get(index))) {
                GridPos anchor=edge.route.get(index);
                List<GridPos> tail=edge.route.subList(index,edge.route.size());
                outgoing.computeIfAbsent(anchor,ignored -> new ArrayList<>()).add(new Edge(anchor,edge.to,tail,edge.revision));
            }
        }
        record Visit(GridPos node, double distance, GridPos firstHop) { }
        Comparator<Visit> order = Comparator.comparingDouble(Visit::distance).thenComparingInt(v -> v.node.x())
                .thenComparingInt(v -> v.node.y()).thenComparingInt(v -> v.node.z());
        var pending = new PriorityQueue<>(order);
        Map<GridPos,Double> costs = new HashMap<>();
        costs.put(start, 0.0); pending.add(new Visit(start,0,null));
        Visit best = null; double bestScore = Double.POSITIVE_INFINITY;
        while (!pending.isEmpty()) {
            Visit visit = pending.remove();
            if (visit.distance > costs.getOrDefault(visit.node, Double.POSITIVE_INFINITY)) continue;
            if (!visit.node.equals(start) && !explored.contains(visit.node) && !rejected.contains(visit.node)) {
                double score = Math.hypot((double)goal.x()-visit.node.x(), (double)goal.z()-visit.node.z())
                        + Math.abs((double)goal.y()-visit.node.y()) + visit.distance*.05;
                if (score < bestScore) { best = visit; bestScore = score; }
            }
            for (Edge edge : outgoing.getOrDefault(visit.node, List.of())) {
                if (rejected.contains(edge.to)) continue;
                double distance = visit.distance + (edge.route.size()-1) * (expired.contains(edge.revision) ? 3 : 1);
                if (distance >= costs.getOrDefault(edge.to, Double.POSITIVE_INFINITY)) continue;
                costs.put(edge.to,distance);
                pending.add(new Visit(edge.to,distance,visit.firstHop == null ? edge.to : visit.firstHop));
            }
        }
        return best == null ? Optional.empty() : Optional.of(best.firstHop);
    }
}
