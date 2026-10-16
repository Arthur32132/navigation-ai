package dev.navigationai.navigation;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionRouteGraphTest {
    private GridPos p(int x,int z) { return new GridPos(x,0,z); }
    @Test void returnsThroughKnownRegionToUntriedBranch() {
        var graph = new RegionRouteGraph(); Object revision = new Object();
        graph.observe(List.of(new GridExploration.Portal(List.of(p(0,0),p(1,0),p(2,0)),true),
                new GridExploration.Portal(List.of(p(0,0),p(0,1),p(0,2)),true)),revision);
        assertEquals(p(0,0),graph.waypoint(p(2,0),p(100,0),Set.of(p(0,0),p(1,0),p(2,0)), key -> true).orElseThrow());
        assertEquals(p(0,2),graph.waypoint(p(0,0),p(100,0),Set.of(p(0,0),p(1,0),p(2,0)), key -> true).orElseThrow());
    }
    @Test void oneWayDropsCannotBecomeBacktrackingEdges() {
        var graph = new RegionRouteGraph();
        graph.observe(List.of(new GridExploration.Portal(List.of(p(0,0),p(1,0),p(2,0)),false)),new Object());
        assertTrue(graph.waypoint(p(2,0),p(-100,0),Set.of(p(2,0)), key -> true).isEmpty());
    }
    @Test void changesInvalidateGeometryAndBlockedWaypointsAreRejected() {
        var graph = new RegionRouteGraph();
        graph.observe(List.of(new GridExploration.Portal(List.of(p(0,0),p(1,0),p(2,0)),true)),new Object());
        assertEquals(p(2,0),graph.waypoint(p(0,0),p(100,0),Set.of(), key -> false).orElseThrow());
        assertEquals(2,graph.unverifiedEdgeCount());
        graph.reject(p(2,0));
        assertTrue(graph.waypoint(p(0,0),p(100,0),Set.of(), key -> false).isEmpty());
    }
    @Test void connectsCurrentPositionInsideAnObservedRoute() {
        var graph = new RegionRouteGraph();
        graph.observe(List.of(new GridExploration.Portal(List.of(p(0,0),p(1,0),p(2,0)),true)),new Object());
        assertEquals(p(2,0),graph.waypoint(p(1,0),p(100,0),Set.of(p(0,0),p(1,0)), key -> true).orElseThrow());
    }
    @Test void memoryHasAFixedUpperBound() {
        var graph = new RegionRouteGraph();
        for (int x=0;x<1000;x++) graph.observe(List.of(new GridExploration.Portal(List.of(p(x,0),p(x+1,0)),true)),new Object());
        assertTrue(graph.edgeCount()<=256);
    }
    @Test void backgroundAnchorsJoinOverlappingPortalRoutes() {
        var graph=new RegionRouteGraph();Object revision=new Object();
        graph.observe(List.of(new GridExploration.Portal(List.of(p(0,0),p(1,0),p(2,0),p(3,0)),true),
                new GridExploration.Portal(List.of(p(2,0),p(3,0),p(4,0),p(5,0)),true),
                new GridExploration.Portal(List.of(p(0,0),p(0,1),p(0,2)),true)),revision);
        assertEquals(p(2,0),graph.waypoint(p(5,0),p(100,0),Set.of(p(0,0),p(1,0),p(2,0),p(3,0),p(4,0),p(5,0)),key -> true).orElseThrow());
    }
}
