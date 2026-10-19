package dev.navigationai.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class JumpEnvelopeTest {
    /** The envelope shortcut only ever answers "clear" when every sample is clear: same verdicts as sampling alone. */
    @Test void envelopeShortcutAgreesWithSampling() {
        var random = new Random(11);
        int clearJumps = 0, blockedJumps = 0;
        for (int round = 0; round < 4000; round++) {
            List<AABB> obstacles = new ArrayList<>();
            for (int n = random.nextInt(4); n > 0; n--) {
                double x = random.nextInt(7) - 1 + random.nextInt(3) * .25, y = random.nextInt(4) - 1 + random.nextInt(4) * .25, z = random.nextInt(5) - 2 + random.nextInt(3) * .25;
                obstacles.add(new AABB(x, y, z, x + .25 + random.nextInt(4) * .25, y + .25 + random.nextInt(4) * .25, z + .25 + random.nextInt(4) * .25));
            }
            Set<BlockPos> hazards = new HashSet<>();
            if (random.nextInt(4) == 0) hazards.add(new BlockPos(random.nextInt(5), 0, random.nextInt(3) - 1));
            Vec3 from = new Vec3(.5, random.nextInt(3) * .25, .5);
            Vec3 to = new Vec3(.5 + 2 + random.nextInt(3), from.y + (random.nextInt(5) - 2) * .25, .5 + random.nextInt(3) - 1);
            java.util.function.Predicate<AABB> clear = box -> obstacles.stream().noneMatch(box::intersects);
            boolean expected = NavigationGeometry.clearJumpSampled(from, to, .6, 1.95, clear, hazards::contains);
            assertEquals(expected, NavigationGeometry.clearJump(from, to, .6, 1.95, clear, hazards::contains),
                    "jump " + from + " -> " + to + " obstacles " + obstacles + " hazards " + hazards);
            if (expected) clearJumps++; else blockedJumps++;
        }
        assertTrue(clearJumps > 200 && blockedJumps > 200, clearJumps + " clear, " + blockedJumps + " blocked");
    }
}
