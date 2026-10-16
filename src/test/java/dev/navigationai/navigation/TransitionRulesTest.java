package dev.navigationai.navigation;

import dev.navigationai.api.NavigationPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransitionRulesTest {
    @Test void diggingUsesAnExposedCardinalFaceBeforeCuttingTheDiagonalCorner() {
        GridWorld world=new GridWorld(){
            public boolean passable(GridPos p){return true;}
            public boolean requiresBreaking(GridPos p){return !p.equals(START);}
        };
        assertNull(TransitionRules.describe(world,START,new GridPos(1,0,1),NavigationPolicy.safeDefaults()));
        assertNotNull(TransitionRules.describe(world,START,new GridPos(1,0,0),NavigationPolicy.safeDefaults()));
    }
    private static final GridPos START = new GridPos(0, 0, 0);
    @Test void almostFullPartialBlockNeedsJumpEvenAtTheSameGridY(){
        GridWorld world=new GridWorld(){
            public boolean passable(GridPos p){return true;}
            public double supportHeight(GridPos p){return p.x()==0?0:15.0/16;}
        };
        var edge=TransitionRules.describe(world,START,new GridPos(1,0,0),NavigationPolicy.safeDefaults());
        assertEquals(GridTransition.Type.JUMP,edge.type());assertTrue(edge.takeoff().grounded());
    }
    @Test void gridStepCannotHideAnAlmostTwoBlockPhysicalRise(){
        GridWorld world=new GridWorld(){
            public boolean passable(GridPos p){return true;}
            public double supportHeight(GridPos p){return p.equals(START)?0:p.y()+15.0/16;}
        };
        assertNull(TransitionRules.describe(world,START,new GridPos(1,1,0),NavigationPolicy.safeDefaults()));
    }
    @Test void plannerCarriesOpeningAndSupportContract() {
        GridWorld world = new GridWorld() {
            @Override public boolean passable(GridPos pos) { return pos.y() == 0 && pos.z() == 0 && pos.x() >= 0 && pos.x() <= 2; }
            @Override public boolean requiresOpening(GridPos pos) { return pos.x() == 1; }
            @Override public double supportHeight(GridPos pos) { return pos.x() == 1 ? .5 : 0; }
        };
        GridPath path = GridPathfinder.find(world, START, new GridPos(2, 0, 0), NavigationPolicy.safeDefaults());
        assertEquals(2, path.transitions().size());
        assertEquals(List.of(GridTransition.Action.OPEN_PASSAGE), path.transitions().getFirst().actions());
        assertEquals(.5, path.transitions().getFirst().toSupportHeight());
        assertTrue(TransitionRules.valid(world, path.transitions().getFirst(), NavigationPolicy.safeDefaults()));
    }
    @Test void rejectsUnsupportedVerticalAirColumn() {
        GridWorld world = pos -> pos.x() == 0 && pos.z() == 0 && pos.y() >= 0 && pos.y() <= 3;
        assertTrue(GridPathfinder.find(world, START, new GridPos(0, 3, 0), NavigationPolicy.safeDefaults()).nodes().isEmpty());
    }
    @Test void changedPoseOrSupportInvalidatesPlannedMovement() {
        GridWorld original = pos -> true;
        GridTransition edge = TransitionRules.describe(original, START, new GridPos(1, 0, 0), NavigationPolicy.safeDefaults());
        GridWorld lowerRoof = new GridWorld() {
            @Override public boolean passable(GridPos pos) { return true; }
            @Override public boolean requiresSneak(GridPos pos) { return pos.x() == 1; }
        };
        assertFalse(TransitionRules.valid(lowerRoof, edge, NavigationPolicy.safeDefaults()));
        GridWorld slab = new GridWorld() {
            @Override public boolean passable(GridPos pos) { return true; }
            @Override public double supportHeight(GridPos pos) { return pos.y() + .5; }
        };
        assertFalse(TransitionRules.valid(slab, edge, NavigationPolicy.safeDefaults()));
    }
    @Test void rejectsLongJumpThroughCrouchOnlyLanding() {
        GridWorld world = new GridWorld() {
            @Override public boolean passable(GridPos pos) { return true; }
            @Override public boolean canJump(GridPos from, GridPos to) { return true; }
            @Override public boolean requiresSneak(GridPos pos) { return pos.x() == 3; }
        };
        assertNull(TransitionRules.describe(world, START, new GridPos(3, 0, 0), NavigationPolicy.safeDefaults()));
    }
    @Test void pathRejectsMismatchedTransitionEndpoints() {
        GridTransition edge = TransitionRules.describe(pos -> true, START, new GridPos(1, 0, 0), NavigationPolicy.safeDefaults());
        assertThrows(IllegalArgumentException.class, () -> new GridPath(List.of(START, new GridPos(2, 0, 0)),
                2, false, true, false, List.of(edge)));
    }
    @Test void removingIntermediateFloorInvalidatesTheOriginalTakeoffConditions() {
        class FloorWorld implements GridWorld {
            boolean gap;
            @Override public boolean passable(GridPos pos) { return true; }
            @Override public boolean canJump(GridPos from, GridPos to) { return true; }
            @Override public boolean crossesGap(GridPos from, GridPos to) { return gap; }
        }
        var world = new FloorWorld();
        GridTransition edge = TransitionRules.describe(world, START, new GridPos(4, 0, 0), NavigationPolicy.safeDefaults());
        assertTrue(TransitionRules.valid(world, edge, NavigationPolicy.safeDefaults()));
        world.gap = true;
        assertFalse(TransitionRules.valid(world, edge, NavigationPolicy.safeDefaults()));
    }
    @Test void swimmingCannotReplaceAGroundedJumpOntoATallDryBank() {
        GridWorld world = new GridWorld() {
            @Override public boolean passable(GridPos pos) { return true; }
            @Override public boolean isWater(GridPos pos) { return pos.equals(START); }
            @Override public double supportHeight(GridPos pos) { return pos.equals(START) ? .89 : 2; }
        };
        assertNull(TransitionRules.describe(world, START, new GridPos(1, 1, 0), NavigationPolicy.safeDefaults()));
    }
}
