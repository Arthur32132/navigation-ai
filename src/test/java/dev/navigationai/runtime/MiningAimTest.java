package dev.navigationai.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MiningAimTest {
    @Test void lowerWallBlockHasVisibleFrontFaceWhenItsCenterIsHiddenByUpperBlock() {
        Vec3 eye = new Vec3(.5,1.62,.5);
        BlockPos target = new BlockPos(1,0,0);
        AABB lower = new AABB(target), upper = new AABB(target.above());
        var firstHitIsLower = (java.util.function.Predicate<Vec3>)point -> {
            var hit = lower.clip(eye,point);
            var obstruction = upper.clip(eye,point);
            return hit.isPresent() && (obstruction.isEmpty()
                    || eye.distanceToSqr(hit.get()) < eye.distanceToSqr(obstruction.get()));
        };
        assertFalse(firstHitIsLower.test(Vec3.atCenterOf(target)));
        Vec3 aim = MiningAim.visiblePoint(target,eye,4.5,firstHitIsLower);
        assertNotNull(aim);
        assertTrue(firstHitIsLower.test(aim));
        assertEquals(1.001,aim.x,1e-9);
    }

    @Test void aCompleteWallCannotBeBypassedBySamplingTargetFaces() {
        Vec3 eye = new Vec3(.5,1.62,.5);
        BlockPos target = new BlockPos(2,0,0);
        AABB behind = new AABB(target), obstruction = new AABB(1,0,0,2,3,1);
        assertNull(MiningAim.visiblePoint(target,eye,4.5,point -> {
            var hit = behind.clip(eye,point);
            var blocked = obstruction.clip(eye,point);
            return hit.isPresent() && (blocked.isEmpty()
                    || eye.distanceToSqr(hit.get()) < eye.distanceToSqr(blocked.get()));
        }));
    }

    @Test void reachUsesTheChosenSurfacePointAndRejectsEveryPointBeyondItsBudget() {
        BlockPos target = new BlockPos(5,0,0);
        Vec3 eye = new Vec3(.5,.5,.5);
        assertTrue(eye.distanceToSqr(Vec3.atCenterOf(target)) > 4.6 * 4.6);
        assertNotNull(MiningAim.visiblePoint(target,eye,4.6,point -> true));
        assertNull(MiningAim.visiblePoint(target,eye,4.4,point -> true));
        assertEquals(4.5*4.5,MiningAim.distanceToBlockSqr(target,eye),1e-9);
    }
}
