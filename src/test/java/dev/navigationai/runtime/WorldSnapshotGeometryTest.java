package dev.navigationai.runtime;

import dev.navigationai.navigation.GridPos;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldSnapshotGeometryTest {
    private WorldSnapshot world(Set<GridPos> passable, Set<GridPos> crouch, Set<GridPos> free,
                                Map<GridPos, List<AABB>> collisions) {
        Set<GridPos> known = new HashSet<>();
        for (int x=-2; x<=6; x++) for (int y=-2; y<=5; y++) for(int z=-2; z<=6; z++) {
            known.add(new GridPos(x,y,z));
        }
        return new WorldSnapshot(passable, Set.of(), crouch, Set.of(), Set.of(),
                free, Map.of(), Map.of(), collisions, known, 0.6, 1.8, Set.of(), 0,
                -8,8,-8,8,-8,8,false);
    }

    @Test void permitsClearOneTwoAndThreeBlockGapJumps() {
        GridPos start = new GridPos(0,0,0);
        for (int length=2; length<=4; length++) {
            GridPos end = new GridPos(length,0,0);
            assertTrue(world(Set.of(start,end),Set.of(),Set.of(),Map.of()).canJump(start,end));
        }
    }

    @Test void rejectsCrouchingTakeoffAndLanding() {
        GridPos start=new GridPos(0,0,0), end=new GridPos(2,0,0);
        assertFalse(world(Set.of(start,end),Set.of(start),Set.of(),Map.of()).canJump(start,end));
        assertFalse(world(Set.of(start,end),Set.of(end),Set.of(),Map.of()).canJump(start,end));
    }

    @Test void rejectsWallEntryBelowItsTop() {
        GridPos start=new GridPos(0,0,0), end=new GridPos(4,0,0);
        Map<GridPos,List<AABB>> walls=Map.of(new GridPos(1,0,0), List.of(new AABB(1,0,0,2,1,1)));
        assertFalse(world(Set.of(start,end),Set.of(),Set.of(),walls).canJump(start,end));
    }

    @Test void checksCeilingOverBothEndpoints() {
        GridPos start=new GridPos(0,0,0), end=new GridPos(4,0,0);
        for (int x : new int[]{0,4}) {
            Map<GridPos,List<AABB>> roof=Map.of(new GridPos(x,2,0),List.of(new AABB(x,2,0,x+1,3,1)));
            assertFalse(world(Set.of(start,end),Set.of(),Set.of(),roof).canJump(start,end));
        }
    }

    @Test void checksTheWidthOfADiagonalJump() {
        GridPos start=new GridPos(0,0,0), end=new GridPos(2,0,2);
        assertTrue(world(Set.of(start,end),Set.of(),Set.of(),Map.of()).canJump(start,end));
        Map<GridPos,List<AABB>> corners=Map.of(new GridPos(1,0,0),List.of(new AABB(1,0,0,2,2,1)),
                new GridPos(0,0,1),List.of(new AABB(0,0,1,1,2,2)));
        assertFalse(world(Set.of(start,end),Set.of(),Set.of(),corners).canJump(start,end));
    }

    @Test void validatesEntryIntoDropColumn() {
        GridPos start=new GridPos(0,3,0), end=new GridPos(1,0,0);
        Set<GridPos> free=Set.of(new GridPos(1,1,0),new GridPos(1,2,0));
        assertFalse(world(Set.of(start,end),Set.of(),free,Map.of()).canDrop(start,end));
        Set<GridPos> clearEntry=new HashSet<>(free);
        clearEntry.add(new GridPos(1,3,0));
        assertTrue(world(Set.of(start,end),Set.of(),clearEntry,Map.of()).canDrop(start,end));
    }

    @Test void startsBodyAboveThePartialSupport() {
        double offset=NavigationGeometry.feetOffset(Shapes.box(0,0,0,1,0.5,1),Shapes.block());
        assertEquals(0.5,offset);
        AABB body=NavigationGeometry.body(0.5,offset,0.5,0.6,1.5);
        assertTrue(body.intersects(new AABB(0,1.8125,0,1,2,1)));
    }
    @Test void supportHeightIncludesAPartialBlockBelowTheCell() {
        assertEquals(-.5, NavigationGeometry.feetOffset(Shapes.empty(),Shapes.box(0,0,0,1,.5,1)));
    }

    @Test void includesRaisedShapesFromTheBlockBelow() {
        GridPos start=new GridPos(0,0,0), end=new GridPos(2,0,0);
        Map<GridPos,List<AABB>> fence=Map.of(new GridPos(0,-1,0),List.of(new AABB(0.375,-1,0.375,0.625,0.5,0.625)));
        assertFalse(world(Set.of(start,end),Set.of(),Set.of(),fence).canJump(start,end));
    }

    @Test void permitsJumpParallelToAnOpenDoorPanel() {
        GridPos start=new GridPos(0,0,0), end=new GridPos(0,0,3);
        Map<GridPos,List<AABB>> panel=Map.of(start,List.of(new AABB(0.8125,0,0,1,2,1)));
        assertTrue(world(Set.of(start,end),Set.of(),Set.of(),panel).canJump(start,end));
    }
    @Test void checksOpenDoorPanelAcrossWalkingExitFace() {
        GridPos start=new GridPos(0,0,0), end=new GridPos(1,0,0);
        Map<GridPos,List<AABB>> panel=Map.of(start,List.of(new AABB(.8125,0,0,1,2,1)));
        WorldSnapshot snapshot=world(Set.of(start,end),Set.of(),Set.of(),panel);
        assertTrue(snapshot.passable(start));
        assertFalse(snapshot.canTraverse(start,end));
    }
}
