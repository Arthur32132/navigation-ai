package dev.navigationai.api;

import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExcavationValidationTest {
    @Test void ascendingEscapeClearsTheTakeoffCeilingAndPreservesBothSupports() {
        var request=new ExcavationRequest(new BlockPos(3,40,7),Direction.SOUTH,ExcavationMode.STAIRCASE_UP,8);
        for(int step=1;step<=8;step++){
            var previous=request.positionAt(step-1);var feet=request.positionAt(step);
            assertEquals(previous.getY()+1,feet.getY());
            assertEquals(previous.above(2),request.clearanceAt(step).getFirst());
            assertTrue(request.clearanceAt(step).contains(feet.above(2)));
            assertFalse(request.clearanceAt(step).contains(previous.below()));
            assertFalse(request.clearanceAt(step).contains(feet.below()));
        }
        assertThrows(IllegalArgumentException.class,()->new ExcavationRequest(new BlockPos(0,Integer.MAX_VALUE-3,0),Direction.EAST,ExcavationMode.STAIRCASE_UP,4));
    }
    @Test void tunnelsPreserveTheirFloorAndDigHeadroomFirstInEveryDirection() {
        BlockPos origin = new BlockPos(-31, 70, 32);
        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            var request = new ExcavationRequest(origin, direction, ExcavationMode.TUNNEL, 16);
            var excavated = new HashSet<BlockPos>();
            for (int step = 1; step <= request.length(); step++) {
                BlockPos feet = origin.relative(direction, step);
                assertEquals(feet, request.positionAt(step));
                assertEquals(java.util.List.of(feet.above(), feet), request.clearanceAt(step));
                assertFalse(request.clearanceAt(step).contains(feet.below()));
                assertFalse(request.clearanceAt(step).contains(request.positionAt(step - 1).below()));
                for (BlockPos cell : request.clearanceAt(step)) assertTrue(excavated.add(cell));
            }
            assertEquals(32, excavated.size());
        }
    }

    @Test void stairsDescendOneBlockAndLeaveEnoughHeadroomForDescendingAndReturning() {
        var request = new ExcavationRequest(new BlockPos(10, 60, 10), Direction.EAST, ExcavationMode.STAIRCASE_DOWN, 12);
        for (int step = 1; step <= request.length(); step++) {
            BlockPos previous = request.positionAt(step - 1), feet = request.positionAt(step);
            assertEquals(previous.getY() - 1, feet.getY());
            assertEquals(java.util.List.of(feet.above(2), feet.above(), feet), request.clearanceAt(step));
            assertEquals(previous.getY() + 1, request.clearanceAt(step).getFirst().getY());
            assertFalse(request.clearanceAt(step).contains(previous.below()));
        }
        var resumed = request.withCompletedSegments(7);
        assertEquals(request.origin(), resumed.origin());
        assertEquals(request.positionAt(7), resumed.positionAt(resumed.completedSegments()));
        assertEquals(12, resumed.length());
    }

    @Test void validatesDirectionLengthProgressAndBudgetsBeforeStartingWork() {
        assertThrows(IllegalArgumentException.class, () -> new ExcavationRequest(BlockPos.ZERO, Direction.DOWN, ExcavationMode.TUNNEL, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExcavationRequest(BlockPos.ZERO, Direction.NORTH, ExcavationMode.TUNNEL, 0));
        assertThrows(IllegalArgumentException.class, () -> new ExcavationRequest(BlockPos.ZERO, Direction.NORTH, ExcavationMode.TUNNEL, 129));
        assertThrows(IllegalArgumentException.class, () -> new ExcavationRequest(BlockPos.ZERO, Direction.NORTH, ExcavationMode.TUNNEL, 3, 4));
        assertThrows(IllegalArgumentException.class, () -> new ExcavationRequest(BlockPos.ZERO, Direction.NORTH, ExcavationMode.TUNNEL, 3).positionAt(-1));
        assertThrows(IllegalArgumentException.class, () -> new ExcavationRequest(new BlockPos(Integer.MAX_VALUE, 60, 0), Direction.EAST, ExcavationMode.TUNNEL, 3));
        assertThrows(IllegalArgumentException.class, () -> new ExcavationPolicy(4097, 100, 1, true, true));
        assertThrows(IllegalArgumentException.class, () -> new ExcavationPolicy(1, 72_001, 1, true, true));
        assertThrows(IllegalArgumentException.class, () -> new ExcavationPolicy(1, 100, 0, true, true));
        // A persisted task can use its remaining movement with no additional block budget.
        assertDoesNotThrow(() -> new ExcavationPolicy(0, 100, 1, true, true));
    }

    @Test void entranceCannotMoveWhenCallerReusesMutableBlockPosition() {
        var mutable = new BlockPos.MutableBlockPos(5, 60, 7);
        var request = new ExcavationRequest(mutable, Direction.WEST, ExcavationMode.TUNNEL, 4);
        mutable.set(100, 200, 300);
        assertEquals(new BlockPos(5, 60, 7), request.origin());
    }
}
