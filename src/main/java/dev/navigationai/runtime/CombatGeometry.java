package dev.navigationai.runtime;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Resolves the first obstruction on an actual view segment, including entities
 * other than the intended target and blocks closer than the entity surface. */
final class CombatGeometry {
    record Hitbox<T>(T entity, AABB box) { }
    private CombatGeometry() { }

    static <T> T firstHit(Vec3 from, Vec3 end, double blockDistanceSquared, List<Hitbox<T>> entities) {
        double closest = blockDistanceSquared;
        T result = null;
        for (Hitbox<T> entity : entities) {
            var intersection = entity.box().clip(from, end);
            double distance = entity.box().contains(from) ? 0
                    : intersection.map(from::distanceToSqr).orElse(Double.POSITIVE_INFINITY);
            if (distance + 1.0E-7 < closest) {
                closest = distance;
                result = entity.entity();
            }
        }
        return result;
    }
}
