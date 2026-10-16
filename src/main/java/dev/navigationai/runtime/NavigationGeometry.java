package dev.navigationai.runtime;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Geometry shared by snapshot planning and live transition validation. */
final class NavigationGeometry {
    private NavigationGeometry() { }
    /** Keep the player's historic margin, but never round a 1.95 m villager above a 2 m doorway. */
    static double clearanceMargin(double height,boolean crouching){
        return Math.min(crouching?.02:.10,Math.max(.005,(Math.ceil(height)-height)/2));
    }

    static double feetOffset(VoxelShape feet, VoxelShape below) {
        if (partialSupport(feet)) return feet.max(Direction.Axis.Y);
        if (!below.isEmpty()) return below.max(Direction.Axis.Y) - 1.0D;
        return 0.0D;
    }
    static boolean partialSupport(VoxelShape shape){return !shape.isEmpty()&&shape.max(Direction.Axis.Y)>0&&shape.max(Direction.Axis.Y)<1.0D;}

    static double feetOffset(ServerLevel level, BlockPos position) {
        var feet = level.getBlockState(position).getCollisionShape(level, position);
        var below = level.getBlockState(position.below()).getCollisionShape(level, position.below());
        var fluid = level.getFluidState(position.below());
        if (feet.isEmpty() && below.isEmpty() && level.getFluidState(position).isEmpty()
                && (fluid.getType() == net.minecraft.world.level.material.Fluids.WATER
                || fluid.getType() == net.minecraft.world.level.material.Fluids.FLOWING_WATER)) {
            return fluid.getHeight(level, position.below()) - 1.0;
        }
        return feetOffset(feet, below);
    }

    static AABB body(double x, double y, double z, double width, double height) {
        double half = width / 2.0D;
        return new AABB(x - half, y + 1.0E-6D, z - half, x + half, y + height, z + half);
    }

    static BlockState planningState(BlockState state, boolean mayOpen) {
        if (mayOpen && state.getBlock() instanceof DoorBlock door && door.type().canOpenByHand()) {
            return state.setValue(DoorBlock.OPEN, true);
        }
        if (mayOpen && state.getBlock() instanceof FenceGateBlock) {
            return state.setValue(FenceGateBlock.OPEN, true);
        }
        return state;
    }

    static boolean bodyFits(ServerLevel level, BlockPos position, double offset, double width,
                            double height, boolean mayOpenDoors) {
        AABB box = body(position.getX() + 0.5D, position.getY() + offset, position.getZ() + 0.5D, width, height);
        for (BlockPos block : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY - 1, box.minZ),
                BlockPos.containing(box.maxX - 1.0E-6D, box.maxY - 1.0E-6D, box.maxZ - 1.0E-6D))) {
            BlockState state = planningState(level.getBlockState(block), mayOpenDoors);
            for (AABB part : state.getCollisionShape(level, block).toAabbs()) {
                if (part.move(block).intersects(box)) return false;
            }
        }
        return true;
    }

    /** Every sample checks the whole body, including entry faces and both endpoints. */
    static boolean clearJump(Vec3 from, Vec3 to, double width, double height,
                             Predicate<AABB> clear, Predicate<BlockPos> dangerous) {
        double distance = Math.hypot(to.x - from.x, to.z - from.z);
        if (distance < 2.0D || distance > 4.0D || Math.abs(from.y - to.y) > 0.5D) return false;
        int samples = (int) Math.ceil(distance * 16.0D);
        AABB previous = null;
        for (int sample = 0; sample <= samples; sample++) {
            double t = sample / (double) samples;
            double x = from.x + (to.x - from.x) * t;
            double z = from.z + (to.z - from.z) * t;
            double y = from.y + (to.y - from.y) * t + 5.0D * t * (1.0D - t);
            AABB sampleBody = body(x, y, z, width, height);
            // Sweep between samples along the actual direction of travel.
            // Isotropic inflation rejected valid jumps parallel to an open door.
            AABB body = previous == null ? sampleBody : sampleBody.minmax(previous);
            previous = sampleBody;
            if (!clear.test(body)) return false;
            int baseY = (int) Math.floor(Math.min(from.y, to.y));
            for (int bx = (int) Math.floor(body.minX); bx <= (int) Math.floor(body.maxX - 1.0E-6D); bx++) {
                for (int bz = (int) Math.floor(body.minZ); bz <= (int) Math.floor(body.maxZ - 1.0E-6D); bz++) {
                    if (dangerous.test(new BlockPos(bx, baseY, bz))) return false;
                }
            }
        }
        return true;
    }
}
