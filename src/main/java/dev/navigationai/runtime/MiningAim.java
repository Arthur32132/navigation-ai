package dev.navigationai.runtime;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Visible face sampling lets a player mine the lower block of a two-block wall. */
final class MiningAim {
    private static final double LOW = .001, HIGH = .999;
    private MiningAim() { }

    static Vec3 visiblePoint(BlockPos position, Vec3 eye, double reach, Predicate<Vec3> hitsTargetFirst) {
        double[][] points = {
                {.5,.5,.5}, {LOW,.5,.5}, {HIGH,.5,.5}, {.5,LOW,.5}, {.5,HIGH,.5}, {.5,.5,LOW}, {.5,.5,HIGH},
                {LOW,LOW,LOW}, {LOW,LOW,HIGH}, {LOW,HIGH,LOW}, {LOW,HIGH,HIGH},
                {HIGH,LOW,LOW}, {HIGH,LOW,HIGH}, {HIGH,HIGH,LOW}, {HIGH,HIGH,HIGH}
        };
        for (double[] point : points) {
            Vec3 target = new Vec3(position.getX() + point[0], position.getY() + point[1], position.getZ() + point[2]);
            if (eye.distanceToSqr(target) <= reach * reach && hitsTargetFirst.test(target)) return target;
        }
        return null;
    }

    static double distanceToBlockSqr(BlockPos position, Vec3 eye) {
        double dx = Math.max(Math.max(position.getX() - eye.x, eye.x - position.getX() - 1), 0);
        double dy = Math.max(Math.max(position.getY() - eye.y, eye.y - position.getY() - 1), 0);
        double dz = Math.max(Math.max(position.getZ() - eye.z, eye.z - position.getZ() - 1), 0);
        return dx * dx + dy * dy + dz * dz;
    }
}
