package dev.navigationai.runtime;

import dev.navigationai.navigation.GridPos;
import dev.navigationai.navigation.GridWorld;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;

/** Immutable, server-thread captured collision snapshot for off-thread A*. */
final class WorldSnapshot implements GridWorld {
    private final Set<GridPos> passable;
    private final Set<GridPos> dangerous;
    private final int minX, maxX, minY, maxY, minZ, maxZ;
    private final boolean water;

    private WorldSnapshot(Set<GridPos> passable, Set<GridPos> dangerous, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, boolean water) {
        this.passable = Set.copyOf(passable); this.dangerous = Set.copyOf(dangerous);
        this.minX = minX; this.maxX = maxX; this.minY = minY; this.maxY = maxY; this.minZ = minZ; this.maxZ = maxZ; this.water = water;
    }

    static WorldSnapshot capture(ServerLevel level, ServerPlayer observer, GridPos start, GridPos goal, int radius, boolean allowWater,
                                 boolean allowChunkGeneration, boolean allowBreaking, boolean allowPlacing) {
        // Keep the snapshot bounded by the requested radius. A distant goal is
        // intentionally outside this local snapshot and will be reported as
        // unreachable instead of making the server scan the whole rectangle.
        int effectiveRadius = allowChunkGeneration ? Math.min(radius, 16) : radius;
        int minX = start.x() - effectiveRadius, maxX = start.x() + effectiveRadius;
        int minZ = start.z() - effectiveRadius, maxZ = start.z() + effectiveRadius;
        int minY = Math.max(level.getMinY(), Math.min(start.y(), goal.y()) - 8);
        int maxY = Math.min(level.getMinY() + level.getHeight() - 2, Math.max(start.y(), goal.y()) + 8);
        Set<GridPos> passable = new HashSet<>();
        Set<GridPos> dangerous = new HashSet<>();
        Set<Long> generatedChunks = new HashSet<>();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) for (int y = minY; y <= maxY; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.hasChunkAt(pos)) {
                if (!allowChunkGeneration) continue;
                // Generation is explicit and remains bounded by snapshotRadius.
                long chunkKey = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
                if (generatedChunks.add(chunkKey)) level.getChunkAt(pos);
                if (!level.hasChunkAt(pos)) continue;
            }
            var state = level.getBlockState(pos);
            var above = level.getBlockState(pos.above());
            var below = level.getBlockState(pos.below());
            FluidState fluidState = level.getFluidState(pos);
            boolean waterCell = fluidState.getType() == Fluids.WATER;
            var stateShape = state.getCollisionShape(level, pos);
            var aboveShape = above.getCollisionShape(level, pos.above());
            boolean emptyBody = stateShape.isEmpty() && aboveShape.isEmpty();
            // A bottom slab, carpet or similar partial block is a valid cell
            // under the player's feet. Full-height stairs/fences remain solid.
            boolean lowSurface = !stateShape.isEmpty() && stateShape.max(Direction.Axis.Y) <= 0.5D
                    && aboveShape.isEmpty();
            boolean climbable = state.is(BlockTags.CLIMBABLE)
                    && (aboveShape.isEmpty() || above.is(BlockTags.CLIMBABLE));
            boolean openable = isClosedWoodenDoor(state) || isClosedWoodenTrapdoor(state);
            boolean openDoor = state.getBlock() instanceof DoorBlock && state.hasProperty(DoorBlock.OPEN)
                    && state.getValue(DoorBlock.OPEN)
                    && (!state.hasProperty(DoorBlock.HALF)
                        || state.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
            FluidState belowFluid = level.getFluidState(pos.below());
            boolean floor = below.getCollisionShape(level, pos.below()).isEmpty()
                    ? (allowWater && belowFluid.getType() == Fluids.WATER)
                    : true;
            GridPos gridPos = new GridPos(x, y, z);
            boolean breakableBody = allowBreaking && isBreakable(level, pos, state)
                    && (above.getCollisionShape(level, pos.above()).isEmpty()
                        || isBreakable(level, pos.above(), above))
                    && !below.getCollisionShape(level, pos.below()).isEmpty();
            boolean breakableAbove = allowBreaking && state.getCollisionShape(level, pos).isEmpty()
                    && isBreakable(level, pos.above(), above)
                    && !below.getCollisionShape(level, pos.below()).isEmpty();
            boolean supportedPlacement = allowPlacing && emptyBody && below.getCollisionShape(level, pos.below()).isEmpty()
                    && (hasSolidNeighbour(level, pos.below()) || hasSolidNeighbour(level, pos));
            if ((emptyBody && floor) || lowSurface || climbable || openable || openDoor
                    || breakableBody || breakableAbove || supportedPlacement
                    || (allowWater && waterCell && aboveShape.isEmpty())) {
                passable.add(gridPos);
            }
            if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CAMPFIRE)
                    || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.MAGMA_BLOCK)
                    || state.is(Blocks.CACTUS) || state.is(Blocks.POWDER_SNOW)
                    || fluidState.getType() == Fluids.LAVA) {
                dangerous.add(gridPos);
            }
        }
        // Living entities are dynamic hazards. Capture their current cells and
        // immediate neighbours so A* prefers a short detour without touching
        // entities from the worker thread later.
        AABB scan = new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, scan)) {
            if (entity == observer || !entity.isAlive() || entity.isRemoved() || entity.isSpectator()) continue;
            BlockPos entityPos = entity.blockPosition();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                GridPos hazard = new GridPos(entityPos.getX() + dx, entityPos.getY(), entityPos.getZ() + dz);
                if (hazard.x() >= minX && hazard.x() <= maxX && hazard.y() >= minY && hazard.y() <= maxY
                        && hazard.z() >= minZ && hazard.z() <= maxZ) dangerous.add(hazard);
            }
        }
        return new WorldSnapshot(passable, dangerous, minX, maxX, minY, maxY, minZ, maxZ, allowWater);
    }

    private static boolean isClosedWoodenDoor(net.minecraft.world.level.block.state.BlockState state) {
        return state.getBlock() instanceof DoorBlock
                && state.hasProperty(DoorBlock.OPEN) && !state.getValue(DoorBlock.OPEN)
                && (!state.hasProperty(DoorBlock.HALF)
                    || state.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
    }

    private static boolean isClosedWoodenTrapdoor(net.minecraft.world.level.block.state.BlockState state) {
        return state.getBlock() instanceof TrapDoorBlock && state.is(BlockTags.WOODEN_TRAPDOORS)
                && state.hasProperty(TrapDoorBlock.OPEN) && !state.getValue(TrapDoorBlock.OPEN);
    }

    private static boolean hasSolidNeighbour(ServerLevel level, BlockPos pos) {
        return !level.getBlockState(pos.east()).getCollisionShape(level, pos.east()).isEmpty()
                || !level.getBlockState(pos.west()).getCollisionShape(level, pos.west()).isEmpty()
                || !level.getBlockState(pos.north()).getCollisionShape(level, pos.north()).isEmpty()
                || !level.getBlockState(pos.south()).getCollisionShape(level, pos.south()).isEmpty();
    }

    private static boolean isBreakable(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        return !state.isAir() && !state.is(Blocks.BEDROCK) && state.getDestroySpeed(level, pos) >= 0.0f;
    }

    @Override public boolean passable(GridPos pos) {
        return pos.x() >= minX && pos.x() <= maxX && pos.y() >= minY && pos.y() <= maxY && pos.z() >= minZ && pos.z() <= maxZ && passable.contains(pos);
    }
    @Override public boolean dangerous(GridPos pos) { return dangerous.contains(pos); }
}
