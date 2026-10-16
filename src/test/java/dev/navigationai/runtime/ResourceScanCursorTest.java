package dev.navigationai.runtime;

import java.util.HashSet;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResourceScanCursorTest {
    @Test void coversEntireCubeOnceInIncreasingShells() {
        for (int radius : new int[]{1, 4, 16, 32}) {
            var cursor = new ResourceScanCursor(radius);
            var seen = new HashSet<BlockPos>();
            BlockPos origin = new BlockPos(-100, 70, 200);
            int previous = 0;
            while (cursor.hasNext()) {
                BlockPos pos = cursor.next(origin);
                int distance = Math.max(Math.abs(pos.getX() - origin.getX()),
                        Math.max(Math.abs(pos.getY() - origin.getY()), Math.abs(pos.getZ() - origin.getZ())));
                assertTrue(distance >= previous && distance <= radius);
                assertTrue(seen.add(pos), "Duplicate: " + pos);
                previous = distance;
            }
            assertEquals((int)Math.pow(radius * 2 + 1, 3), seen.size());
        }
    }
    @Test void nearbyResourceIsVisitedWithinFirstBudgetEvenAtMaximumRadius() {
        var cursor = new ResourceScanCursor(32);
        boolean found = false;
        for (int i = 0; i < 512; i++) if (cursor.next(BlockPos.ZERO).equals(new BlockPos(2, 0, 0))) found = true;
        assertTrue(found);
        assertTrue(cursor.hasNext());
    }
}
