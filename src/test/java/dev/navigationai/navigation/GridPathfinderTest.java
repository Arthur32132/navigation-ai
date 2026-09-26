package dev.navigationai.navigation;

import dev.navigationai.api.NavigationPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GridPathfinderTest {
    @Test void zeroFallLimitRejectsOneBlockDropButAllowsLadderDescent() {
        GridPos start=new GridPos(0,1,0), goal=new GridPos(1,0,0);
        NavigationPolicy noFalls=new NavigationPolicy(false,false,false,false,512,16,8,0,0);
        GridWorld falling=pos -> pos.equals(start) || pos.equals(goal);
        assertTrue(GridPathfinder.find(falling,start,goal,noFalls).nodes().isEmpty());
        GridWorld ladder=new GridWorld() {
            public boolean passable(GridPos p) { return falling.passable(p); }
            public boolean canDescendWithoutFalling(GridPos from,GridPos to) { return true; }
        };
        assertEquals(List.of(start,goal),GridPathfinder.find(ladder,start,goal,noFalls).nodes());
    }

    @Test void keepsShorterMoreExpensiveRouteUnderLengthLimit() {
        GridPos s=new GridPos(0,0,0), a=new GridPos(1,0,0), b=new GridPos(0,1,0);
        GridPos c=new GridPos(1,1,0), m=new GridPos(2,0,0), g=new GridPos(3,0,0);
        Map<GridPos,Set<GridPos>> edges=Map.of(s,Set.of(a,b),a,Set.of(m),b,Set.of(c),c,Set.of(m),m,Set.of(g),g,Set.of());
        GridWorld world=new GridWorld() {
            public boolean passable(GridPos p) { return edges.containsKey(p); }
            public boolean canTraverse(GridPos from,GridPos to) { return edges.getOrDefault(from,Set.of()).contains(to); }
            public double cost(GridPos from,GridPos to) { return GridWorld.super.cost(from,to)+(to.equals(a)?20:0); }
        };
        assertEquals(List.of(s,a,m,g),GridPathfinder.find(world,s,g,policy(512,3,0)).nodes());
    }

    @Test void searchesAllApproachesWhenFirstEndpointIsUnreachable() {
        GridPos start=new GridPos(0,0,0), reachable=new GridPos(2,0,0), isolated=new GridPos(2,2,0);
        GridWorld world=p -> p.equals(isolated) || p.y()==0 && p.z()==0 && p.x()>=0 && p.x()<=2;
        GridPath result=GridPathfinder.findToAny(world,start,Set.of(isolated,reachable),
                new GridPos(2,1,0),policy(512),NodeBudget.unlimited(),false);
        assertTrue(result.goalReached());
        assertEquals(reachable,result.nodes().getLast());
    }

    @Test void refusesBridgeLongerThanAvailableMaterials() {
        GridWorld world=new GridWorld() {
            public boolean passable(GridPos p) { return p.y()==0 && p.z()==0 && p.x()>=0 && p.x()<=3; }
            public boolean requiresPlacement(GridPos p) { return p.x()==1 || p.x()==2; }
            public int maxPlacements() { return 1; }
        };
        assertTrue(GridPathfinder.find(world,new GridPos(0,0,0),new GridPos(3,0,0),policy(512)).nodes().isEmpty());
    }
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
    void reportsCooperativeGlobalBudgetExhaustionSeparatelyFromUnreachable() {
        GridWorld world = pos -> Math.abs(pos.x()) < 100 && Math.abs(pos.z()) < 100 && pos.y() == 0;
        AtomicInteger remaining = new AtomicInteger(3);

        GridPath path = GridPathfinder.find(world, new GridPos(0, 0, 0), new GridPos(90, 0, 90),
                policy(10_000), () -> remaining.getAndDecrement() > 0);

        assertTrue(path.nodes().isEmpty());
        assertEquals(3, path.nodesVisited());
        assertTrue(path.budgetExhausted());
    }

    @Test
    void returnsAUsefulFrontierWhenTheGoalIsOutsideTheKnownWorld() {
        GridWorld world = pos -> pos.y() == 0 && Math.abs(pos.x()) <= 4 && Math.abs(pos.z()) <= 4;

        GridPath path = GridPathfinder.find(world, new GridPos(0, 0, 0), new GridPos(100, 0, 0),
                policy(10_000), NodeBudget.unlimited(), true);

        assertFalse(path.nodes().isEmpty());
        assertTrue(path.partial());
        assertFalse(path.goalReached());
        assertTrue(path.nodes().getLast().x() > 0);
        assertFalse(path.budgetExhausted());
    }

    @Test
    void keepsCompleteResultDistinctFromAFrontierResult() {
        GridWorld world = pos -> pos.y() == 0 && pos.x() >= 0 && pos.x() <= 4 && pos.z() == 0;

        GridPath path = GridPathfinder.find(world, new GridPos(0, 0, 0), new GridPos(4, 0, 0),
                policy(10_000), NodeBudget.unlimited(), true);

        assertEquals(new GridPos(4, 0, 0), path.nodes().getLast());
        assertTrue(path.goalReached());
        assertFalse(path.partial());
    }

    @Test
    void returnsFrontierBeforeTheSharedBudgetIsLost() {
        GridWorld world = pos -> pos.y() == 0 && Math.abs(pos.x()) <= 32 && Math.abs(pos.z()) <= 32;
        AtomicInteger remaining = new AtomicInteger(8);

        GridPath path = GridPathfinder.find(world, new GridPos(0, 0, 0), new GridPos(100, 0, 0),
                policy(10_000), () -> remaining.getAndDecrement() > 0, true);

        assertFalse(path.nodes().isEmpty());
        assertTrue(path.partial());
        assertTrue(path.budgetExhausted());
        assertFalse(path.goalReached());
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
    void avoidsAVerticalStepTheWorldCannotPhysicallyClear() {
        GridPos start = new GridPos(0, 0, 0);
        GridPos raisedWall = new GridPos(1, 1, 0);
        GridPos goal = new GridPos(2, 0, 0);
        GridWorld world = new GridWorld() {
            @Override public boolean passable(GridPos pos) {
                return pos.equals(start) || pos.equals(raisedWall) || pos.equals(goal)
                        || (pos.y() == 0 && pos.x() >= 0 && pos.x() <= 2 && pos.z() == 1);
            }

            @Override public boolean canTraverse(GridPos from, GridPos to) {
                return GridWorld.super.canTraverse(from, to)
                        && !(from.equals(start) && to.equals(raisedWall));
            }
        };

        GridPath path = GridPathfinder.find(world, start, goal, policy(256));

        assertEquals(goal, path.nodes().getLast());
        assertFalse(path.nodes().contains(raisedWall));
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
        GridPath path = GridPathfinder.find(world, new GridPos(0, 0, 0), new GridPos(4, 0, 0),
                policy(128, 3, 0), NodeBudget.unlimited(), true);

        assertEquals(List.of(new GridPos(0, 0, 0), new GridPos(1, 0, 0),
                new GridPos(2, 0, 0), new GridPos(3, 0, 0)), path.nodes());
        assertTrue(path.partial());
        assertFalse(path.goalReached());
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
        GridWorld world = new GridWorld() {
            @Override public boolean passable(GridPos pos) {
                return pos.x() == 0 && pos.z() == 0 && pos.y() >= 0 && pos.y() <= 3;
            }
            @Override public boolean isClimbable(GridPos pos) { return passable(pos); }
        };

        GridPath path = GridPathfinder.find(world, start, goal, policy(128));

        assertEquals(List.of(start, new GridPos(0, 1, 0), new GridPos(0, 2, 0), goal), path.nodes());
    }

    @Test
    void plansAControlledDropOnlyWhenTheWorldAllowsIt() {
        GridPos start = new GridPos(0, 3, 0);
        GridPos goal = new GridPos(1, 0, 0);
        GridWorld world = new GridWorld() {
            @Override public boolean passable(GridPos pos) {
                return pos.equals(start) || pos.equals(goal);
            }

            @Override public boolean canDrop(GridPos from, GridPos to) {
                return from.equals(start) && to.equals(goal);
            }
        };

        GridPath path = GridPathfinder.find(world, start, goal,
                new NavigationPolicy(false, false, false, false, 512, 32, 8, 0, 3));

        assertEquals(List.of(start, goal), path.nodes());
        assertTrue(path.goalReached());
    }

    @Test
    void doesNotInventAPathAcrossAnUnverifiedDrop() {
        GridPos start = new GridPos(0, 3, 0);
        GridPos goal = new GridPos(1, 0, 0);
        GridWorld world = pos -> pos.equals(start) || pos.equals(goal);

        GridPath path = GridPathfinder.find(world, start, goal,
                new NavigationPolicy(false, false, false, false, 512, 32, 8, 0, 3));

        assertTrue(path.nodes().isEmpty());
    }

    @Test
    void jumpsAcrossOneTwoAndThreeBlockGaps() {
        for (int gap = 1; gap <= 3; gap++) {
            GridPos start = new GridPos(0, 0, 0);
            GridPos goal = new GridPos(gap + 1, 0, 0);
            GridWorld world = new GridWorld() {
                @Override public boolean passable(GridPos pos) {
                    return pos.equals(start) || pos.equals(goal);
                }

                @Override public boolean canJump(GridPos from, GridPos to) {
                    return from.equals(start) && to.equals(goal);
                }
            };

            GridPath path = GridPathfinder.find(world, start, goal, policy(128));

            assertEquals(List.of(start, goal), path.nodes(), "gap=" + gap);
        }
    }

    @Test
    void doesNotJumpAcrossLongerGap() {
        GridPos start = new GridPos(0, 0, 0);
        GridPos goal = new GridPos(5, 0, 0);
        GridWorld world = new GridWorld() {
            @Override public boolean passable(GridPos pos) {
                return pos.equals(start) || pos.equals(goal);
            }

            @Override public boolean canJump(GridPos from, GridPos to) {
                return true;
            }
        };

        GridPath path = GridPathfinder.find(world, start, goal, policy(128));

        assertTrue(path.nodes().isEmpty());
    }

    @Test
    void jumpsDiagonallyWhenTheWorldValidatesTheArc() {
        GridPos start = new GridPos(0, 0, 0);
        GridPos goal = new GridPos(2, 0, 2);
        GridWorld world = new GridWorld() {
            @Override public boolean passable(GridPos pos) {
                return pos.equals(start) || pos.equals(goal);
            }

            @Override public boolean canJump(GridPos from, GridPos to) {
                return from.equals(start) && to.equals(goal);
            }
        };

        GridPath path = GridPathfinder.find(world, start, goal, policy(128));

        assertEquals(List.of(start, goal), path.nodes());
    }

    @Test
    void doesNotPlanDiagonalJumpsBeyondTheSprintRange() {
        GridPos start = new GridPos(0, 0, 0);
        GridPos goal = new GridPos(3, 0, 3);
        GridWorld world = new GridWorld() {
            @Override public boolean passable(GridPos pos) {
                return pos.equals(start) || pos.equals(goal);
            }

            @Override public boolean canJump(GridPos from, GridPos to) { return true; }
        };

        GridPath path = GridPathfinder.find(world, start, goal, policy(128));

        assertTrue(path.nodes().isEmpty());
    }

    @Test
    void limitsWaterAndPrefersDryDetour() {
        GridPos start = new GridPos(0, 0, 0);
        GridPos goal = new GridPos(4, 0, 0);
        GridWorld world = new GridWorld() {
            @Override public boolean passable(GridPos pos) {
                return pos.y() == 0 && pos.x() >= 0 && pos.x() <= 4 && Math.abs(pos.z()) <= 1;
            }

            @Override public boolean isWater(GridPos pos) {
                return pos.y() == 0 && pos.z() == 0 && pos.x() >= 1 && pos.x() <= 3;
            }

            @Override public int maxWaterSteps() { return 8; }

            @Override public double cost(GridPos from, GridPos to) {
                return GridWorld.super.cost(from, to) + (isWater(to) ? 3.0 : 0.0);
            }
        };

        GridPath path = GridPathfinder.find(world, start, goal, policy(512));

        assertFalse(path.nodes().isEmpty());
        assertTrue(path.nodes().stream().noneMatch(pos -> pos.z() == 0 && pos.x() >= 1 && pos.x() <= 3));
    }
}
