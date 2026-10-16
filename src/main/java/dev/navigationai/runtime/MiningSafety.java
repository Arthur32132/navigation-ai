package dev.navigationai.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;

/** Shared conservative checks for autonomous resource work. Reads loaded chunks only. */
final class MiningSafety {
    private MiningSafety() { }

    static boolean supportsPlayer(LivingEntity player, BlockPos position) {
        AABB body = player.getBoundingBox();
        // Position alone is insufficient: powder snow and other empty collision shapes are not a
        // supporting floor. Treating them as support prevented trapped villagers from digging out.
        var shape=player.level().getBlockState(position).getCollisionShape(player.level(),position,net.minecraft.world.phys.shapes.CollisionContext.of(player));
        var feet=new AABB(body.minX,body.minY-.15,body.minZ,body.maxX,body.minY+.001,body.maxZ);
        if(shape.toAabbs().stream().noneMatch(box->box.move(position).intersects(feet)))return false;
        return position.getY() < body.minY && position.getY() + 1 >= body.minY - .15
                && body.maxX > position.getX() && body.minX < position.getX() + 1
                && body.maxZ > position.getZ() && body.minZ < position.getZ() + 1;
    }

    static String excavationFailure(ServerLevel level, LivingEntity player, BlockPos position) {
        if (!loaded(level, position)) return "unloaded_chunk";
        if (position.getY() < level.getMinY() || position.getY() >= level.getMaxY()) return "world_bounds";
        if (!level.getWorldBorder().isWithinBounds(position)) return "world_border";
        if (supportsPlayer(player, position)) return "unsafe_support";
        if (!ActorSupport.mayBreak(player, level, position)) return "not_allowed";
        var state = level.getBlockState(position);
        if (state.hasBlockEntity()) return "protected_block_entity";
        if (NavigationSafety.dangerous(state)) return "dangerous_block";
        if (state.getBlock() instanceof FallingBlock) return "falling_block";
        if (!level.getFluidState(position).isEmpty()) return "unsafe_fluid";
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = position.relative(direction);
            if (!loaded(level, neighbour)) return "unloaded_chunk";
            if (!level.getFluidState(neighbour).isEmpty()) return "unsafe_fluid";
        }
        if (level.getBlockState(position.above()).getBlock() instanceof FallingBlock) return "unstable_ceiling";
        return null;
    }

    static boolean loaded(ServerLevel level, BlockPos position) {
        return level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4) != null;
    }
}
