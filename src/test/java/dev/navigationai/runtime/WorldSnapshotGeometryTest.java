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
        return world(passable, crouch, free, collisions, Set.of());
    }

    private WorldSnapshot world(Set<GridPos> passable, Set<GridPos> crouch, Set<GridPos> free,
                                Map<GridPos, List<AABB>> collisions, Set<GridPos> hazards) {
        Set<GridPos> known = new HashSet<>();
        for (int x=-2; x<=6; x++) for (int y=-2; y<=5; y++) for(int z=-2; z<=6; z++) {
            known.add(new GridPos(x,y,z));
        }
        return new WorldSnapshot(passable, hazards, crouch, Set.of(), Set.of(),
                free, Map.of(), Map.of(), collisions, known, 0.6, 1.8, Set.of(), 0,
                -8,8,-8,8,-8,8,false);
    }

    @Test void rejectsDiagonalBodyContactWithHazardBetweenSafeEndpoints() {
        GridPos start = new GridPos(0,0,0), end = new GridPos(1,0,1);
        GridPos corner = new GridPos(1,0,0), detour = new GridPos(0,0,1);
        Set<GridPos> cells = Set.of(start, end, corner, detour);
        assertTrue(world(cells,Set.of(),Set.of(),Map.of()).canTraverse(start,end));
        WorldSnapshot hazardous = world(cells,Set.of(),Set.of(),Map.of(),Set.of(corner));
        assertFalse(hazardous.canTraverse(start,end));
        assertTrue(hazardous.canTraverse(start,detour));
        assertTrue(hazardous.canTraverse(detour,end));
        assertTrue(hazardous.canTraverse(corner,end), "A bot already inside a hazard can leave it");
    }

    @Test void permitsClearOneTwoAndThreeBlockGapJumps() {
        GridPos start = new GridPos(0,0,0);
        for (int length=2; length<=4; length++) {
            GridPos end = new GridPos(length,0,0);
            assertTrue(world(Set.of(start,end),Set.of(),Set.of(),Map.of()).canJump(start,end));
        }
    }

    @Test void rejectsJumpArcAndWalkingSweepThroughAnotherEntity() throws Exception {
        GridPos start = new GridPos(0,0,0), end = new GridPos(4,0,0);
        WorldSnapshot snapshot = world(Set.of(start,end,new GridPos(1,0,0)),Set.of(),Set.of(),Map.of());
        assertTrue(snapshot.canJump(start,end));
        var field = WorldSnapshot.class.getDeclaredField("entityCollisions");
        field.setAccessible(true);
        field.set(snapshot,List.of(new AABB(1.7,0,.2,2.3,1.8,.8)));
        assertFalse(snapshot.canJump(start,end));
        field.set(snapshot,List.of(new AABB(.8,0,.2,1.4,1.8,.8)));
        assertFalse(snapshot.canTraverse(start,new GridPos(1,0,0)));
        field.set(snapshot,List.of(new AABB(1.7,0,2.2,2.3,1.8,2.8)));
        assertTrue(snapshot.canJump(start,end));
    }

    @Test void trunkAtHeadHeightRulesOutTheJumpWithoutSweepingTheArc() {
        GridPos start = new GridPos(0,0,0), end = new GridPos(4,0,0);
        Map<GridPos,List<AABB>> trunk = Map.of(new GridPos(2,0,0), List.of(new AABB(2,0,0,3,1,1)),
                new GridPos(2,1,0), List.of(new AABB(2,1,0,3,2,1)));
        WorldSnapshot snapshot = world(Set.of(start,end),Set.of(),Set.of(),trunk);
        assertEquals(WorldSnapshot.JUMP_BLOCKED, snapshot.jumpObstacle(start,end));
        assertFalse(snapshot.clearArc(start,end));
        assertFalse(snapshot.canJump(start,end));
        // A post that does not fill the cell's footprint is left to the full sweep.
        Map<GridPos,List<AABB>> post = Map.of(new GridPos(2,1,0), List.of(new AABB(2.4,1,.4,2.6,2,.6)));
        assertEquals(WorldSnapshot.JUMP_NOT_NEEDED, world(Set.of(start,end,new GridPos(1,0,0),new GridPos(2,0,0),new GridPos(3,0,0)),
                Set.of(),Set.of(),post).jumpObstacle(start,end));
    }

    @Test void headHeightShortcutNeverRejectsAJumpTheArcWouldClear() {
        var random = new java.util.Random(7);
        GridPos start = new GridPos(0,0,0);
        int blocked = 0;
        for (int round = 0; round < 3000; round++) {
            GridPos end = new GridPos(2 + random.nextInt(3), 0, random.nextInt(3) - 1);
            Map<GridPos,List<AABB>> shapes = new java.util.HashMap<>();
            for (int n = 0; n < 3; n++) {
                int x = random.nextInt(5), y = random.nextInt(3), z = random.nextInt(3) - 1;
                double low = random.nextInt(4) / 4.0, high = low + .25 + random.nextInt(4) / 4.0;
                double inset = random.nextBoolean() ? 0 : .3;
                shapes.put(new GridPos(x,y,z), List.of(new AABB(x + inset, y + low, z + inset, x + 1 - inset, y + Math.min(1, high), z + 1 - inset)));
            }
            shapes.remove(start); shapes.remove(end);
            WorldSnapshot snapshot = world(Set.of(start,end),Set.of(),Set.of(),shapes);
            if (snapshot.jumpObstacle(start,end) == WorldSnapshot.JUMP_BLOCKED) {
                blocked++;
                assertFalse(snapshot.clearArc(start,end), "shortcut rejected a clear jump to " + end + " over " + shapes);
            }
        }
        assertTrue(blocked > 100);
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

    @Test void checksHeadClearanceBeforeASingleBlockDescent() {
        GridPos start=new GridPos(0,1,0), end=new GridPos(1,0,0);
        assertTrue(world(Set.of(start,end),Set.of(),Set.of(),Map.of()).canTraverse(start,end));
        Map<GridPos,List<AABB>> roof=Map.of(new GridPos(1,2,0),List.of(new AABB(1,2,0,2,3,1)));
        WorldSnapshot snapshot=world(Set.of(start,end),Set.of(),Set.of(),roof);
        assertTrue(snapshot.passable(end),"Landing fits below the roof");
        assertFalse(snapshot.canTraverse(start,end),"The body must enter before it can fall");
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
    @Test void farmlandAndPathsSupportTheBodyAboveTheirActualSurface(){
        var farmland=Shapes.box(0,0,0,1,15.0/16,1);
        assertTrue(NavigationGeometry.partialSupport(farmland));
        double offset=NavigationGeometry.feetOffset(farmland,Shapes.block());assertEquals(15.0/16,offset);
        var body=NavigationGeometry.body(.5,offset,.5,.6,1.95);
        assertFalse(body.intersects(new AABB(0,0,0,1,15.0/16,1)));
        assertFalse(NavigationGeometry.partialSupport(Shapes.block()));
        assertFalse(NavigationGeometry.partialSupport(Shapes.box(0,0,0,1,1.5,1)));
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
