package dev.navigationai.runtime;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatGeometryTest {
    private final Vec3 eye = new Vec3(0, 1.6, 0);
    private CombatGeometry.Hitbox<String> entity(String name, double x) {
        return new CombatGeometry.Hitbox<>(name, new AABB(x, 0, -0.3, x + 0.6, 1.8, 0.3));
    }

    @Test void nearerEntityBlocksAttackOnTargetBehindItRegardlessOfIterationOrder() {
        var target = entity("target", 2.2);
        var bystander = entity("bystander", 1.0);
        assertEquals("bystander", CombatGeometry.firstHit(eye, new Vec3(3,1.6,0),
                Double.POSITIVE_INFINITY, List.of(target, bystander)));
    }

    @Test void nearerWallBlocksTheActualViewRayEvenWhenTargetCenterIsVisibleElsewhere() {
        assertNull(CombatGeometry.firstHit(eye, new Vec3(3,1.6,0), 1.5 * 1.5,
                List.of(entity("target", 2.2))));
        assertEquals("target", CombatGeometry.firstHit(eye, new Vec3(3,1.6,0), 2.9 * 2.9,
                List.of(entity("target", 2.2))));
    }

    @Test void viewSegmentDoesNotAddHiddenExtraReachOrHitboxInflation() {
        assertNull(CombatGeometry.firstHit(eye, new Vec3(3,1.6,0), Double.POSITIVE_INFINITY,
                List.of(entity("target", 3.01))));
        assertEquals("target", CombatGeometry.firstHit(eye, new Vec3(3,1.6,0), Double.POSITIVE_INFINITY,
                List.of(entity("target", 2.99))));
        assertNull(CombatGeometry.firstHit(eye, new Vec3(3,1.6,1), Double.POSITIVE_INFINITY,
                List.of(entity("target", 2.5))));
    }

    @Test void overlappingEntityAtRayOriginIsTheFirstHit() {
        assertEquals("near", CombatGeometry.firstHit(eye, new Vec3(3,1.6,0), Double.POSITIVE_INFINITY,
                List.of(entity("far", 2.5), entity("near", -0.1))));
    }
}
