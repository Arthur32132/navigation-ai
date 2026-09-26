package dev.navigationai.runtime;

import dev.navigationai.navigation.GridPos;
import dev.navigationai.navigation.GridWorld;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;

/** Immutable, server-thread captured collision snapshot for off-thread A*. */
final class WorldSnapshot implements GridWorld {
    private static final double WATER_STEP_COST = 3.0D;
    private static final double STANDING_CLEARANCE_MARGIN = 0.10D;
    private static final double CROUCHING_CLEARANCE_MARGIN = 0.02D;
    private static final int VERTICAL_SNAPSHOT_RADIUS = 16;
    private static final int MAX_VERTICAL_GOAL_RADIUS = 32;
    private final Set<GridPos> passable;
    private final Set<GridPos> dangerous;
    private final Set<GridPos> crouchOnly;
    private final Set<GridPos> climbableCells;
    private final Set<GridPos> waterCells;
    private final Set<GridPos> freeBody;
    private final Map<GridPos, Double> actionCosts;
    private final Map<GridPos, Double> surfaceOffsets;
    private final Map<GridPos, List<AABB>> collisions;
    private final double bodyWidth, standingHeight;
    private final Set<GridPos> knownCells;
    private final Set<GridPos> placementCells;
    private final int availablePlacements;
    private final int minX, maxX, minY, maxY, minZ, maxZ;
    private final boolean hasUnloadedChunks;

    WorldSnapshot(Set<GridPos> passable, Set<GridPos> dangerous,
                          Set<GridPos> crouchOnly, Set<GridPos> climbableCells, Set<GridPos> waterCells,
                          Set<GridPos> freeBody,
                          Map<GridPos, Double> actionCosts, Map<GridPos, Double> surfaceOffsets,
                          Map<GridPos, List<AABB>> collisions, Set<GridPos> knownCells,
                          double bodyWidth, double standingHeight, Set<GridPos> placementCells, int availablePlacements,
                          int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                          boolean hasUnloadedChunks) {
        // Capture owns these collections and stops mutating them after this
        // point. Reusing them avoids a large final copy on the server tick.
        this.passable = passable;
        this.dangerous = dangerous;
        this.crouchOnly = crouchOnly;
        this.climbableCells = climbableCells;
        this.waterCells = waterCells;
        this.freeBody = freeBody;
        this.actionCosts = actionCosts;
        this.surfaceOffsets = surfaceOffsets;
        this.collisions = collisions;
        this.bodyWidth = bodyWidth;
        this.standingHeight = standingHeight;
        this.knownCells = knownCells;
        this.placementCells = placementCells;
        this.availablePlacements = availablePlacements;
        this.minX = minX; this.maxX = maxX; this.minY = minY; this.maxY = maxY; this.minZ = minZ; this.maxZ = maxZ;
        this.hasUnloadedChunks = hasUnloadedChunks;
    }

    static WorldSnapshot capture(ServerLevel level, ServerPlayer observer, GridPos start, int radius, boolean allowWater,
                                 boolean allowChunkGeneration, boolean allowBreaking, boolean allowPlacing,
                                 BooleanSupplier generationBudget) {
        Capture capture = beginCapture(level, observer, start, radius, allowWater,
                allowChunkGeneration, allowBreaking, allowPlacing, generationBudget);
        while (!capture.step(Integer.MAX_VALUE)) { }
        return capture.result();
    }

    static Capture beginCapture(ServerLevel level, ServerPlayer observer, GridPos start, int radius,
                                boolean allowWater, boolean allowChunkGeneration, boolean allowBreaking,
                                boolean allowPlacing, BooleanSupplier generationBudget) {
        return beginCapture(level, observer, start, null, radius, allowWater,
                allowChunkGeneration, allowBreaking, allowPlacing, generationBudget);
    }

    static Capture beginCapture(ServerLevel level, ServerPlayer observer, GridPos start, GridPos destination,
                                int radius, boolean allowWater, boolean allowChunkGeneration,
                                boolean allowBreaking, boolean allowPlacing, BooleanSupplier generationBudget) {
        return new Capture(level, observer, start, radius, allowWater,
                allowChunkGeneration, allowBreaking, allowPlacing, generationBudget, destination);
    }

    /** Scans a bounded number of cells per server tick before publishing an immutable snapshot. */
    static final class Capture {
        private final ServerLevel level;
        private final ServerPlayer observer;
        private final GridPos start;
        private final GridPos destination;
        private final boolean allowWater, allowChunkGeneration, allowBreaking, allowPlacing;
        private final BooleanSupplier generationBudget;
        private final int minX, maxX, minY, maxY, minZ, maxZ;
        private final Set<GridPos> passable = new HashSet<>();
        private final Set<GridPos> dangerous = new HashSet<>();
        private final Set<GridPos> crouchOnly = new HashSet<>();
        private final Set<GridPos> climbableCells = new HashSet<>();
        private final Set<GridPos> waterCells = new HashSet<>();
        private final Set<GridPos> freeBody = new HashSet<>();
        private final Map<GridPos, Double> actionCosts = new HashMap<>();
        private final Map<GridPos, Double> surfaceOffsets = new HashMap<>();
        private final Map<GridPos, List<AABB>> collisions = new HashMap<>();
        private final Set<GridPos> knownCells = new HashSet<>();
        private final Set<GridPos> placementCells = new HashSet<>();
        private final int availablePlacements;
        private final Set<Long> generatedChunks = new HashSet<>();
        private final double standingHeight, crouchingHeight, bodyWidth;
        private int x, y, z;
        private boolean hasUnloadedChunks;
        private WorldSnapshot result;

        private Capture(ServerLevel level, ServerPlayer observer, GridPos start, int radius, boolean allowWater,
                        boolean allowChunkGeneration, boolean allowBreaking, boolean allowPlacing,
                        BooleanSupplier generationBudget, GridPos destination) {
            this.level = level;
            this.observer = observer;
            this.start = start;
            this.destination = destination;
            this.allowWater = allowWater;
            this.allowChunkGeneration = allowChunkGeneration;
            this.allowBreaking = allowBreaking;
            this.allowPlacing = allowPlacing;
            this.availablePlacements = allowPlacing ? NavigationSafety.availableBuildingBlocks(observer, level) : 0;
            this.generationBudget = generationBudget;
            // Keep the snapshot bounded by the requested radius. A distant goal is
            // intentionally outside this local snapshot; the pathfinder handles it
            // as a frontier target instead of making the server scan the whole
            // rectangle.
            int effectiveRadius = radius;
            minX = start.x() - effectiveRadius; maxX = start.x() + effectiveRadius;
            minZ = start.z() - effectiveRadius; maxZ = start.z() + effectiveRadius;
            // Include a nearby elevated or lowered destination in the scan.
            // Otherwise an endpoint twenty blocks above the bot is invisible
            // even while it is directly overhead, so a partial route heads
            // to the ground below it instead of finding the access route.
            int goalRadius = destination == null ? VERTICAL_SNAPSHOT_RADIUS
                    : (int) Math.min(MAX_VERTICAL_GOAL_RADIUS,
                            Math.abs((long) destination.y() - start.y()) + 4L);
            int belowRadius = destination != null && destination.y() < start.y()
                    ? Math.max(VERTICAL_SNAPSHOT_RADIUS, goalRadius) : VERTICAL_SNAPSHOT_RADIUS;
            int aboveRadius = destination != null && destination.y() > start.y()
                    ? Math.max(VERTICAL_SNAPSHOT_RADIUS, goalRadius) : VERTICAL_SNAPSHOT_RADIUS;
            minY = Math.max(level.getMinY(), start.y() - belowRadius);
            maxY = Math.min(level.getMinY() + level.getHeight() - 2,
                    start.y() + aboveRadius);
            standingHeight = observer.getDimensions(Pose.STANDING).height();
            crouchingHeight = observer.getDimensions(Pose.CROUCHING).height();
            bodyWidth = observer.getDimensions(Pose.STANDING).width();
            x = minX; y = minY; z = minZ;
        }

        boolean step(int maxCells) { return step(maxCells, Long.MAX_VALUE); }

        boolean step(int maxCells, long deadlineNanos) {
            if (result != null) return true;
            int processed = 0;
            while (x <= maxX && processed++ < maxCells) {
                if (System.nanoTime() >= deadlineNanos) return false;
                int x = this.x, y = this.y, z = this.z;
                if (++this.y > maxY) {
                    this.y = minY;
                    if (++this.z > maxZ) { this.z = minZ; this.x++; }
                }
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.hasChunkAt(pos)) {
                    if (!allowChunkGeneration) {
                        hasUnloadedChunks = true;
                        continue;
                    }
                    // Generation is explicit and remains bounded by snapshotRadius.
                    long chunkKey = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
                    if (!generatedChunks.contains(chunkKey)
                            && (generationBudget == null || generationBudget.getAsBoolean())) {
                        generatedChunks.add(chunkKey);
                        level.getChunkAt(pos);
                    }
                    if (!level.hasChunkAt(pos)) {
                        hasUnloadedChunks = true;
                        continue;
                    }
                }
                var state = level.getBlockState(pos);
                var above = level.getBlockState(pos.above());
                var below = level.getBlockState(pos.below());
                FluidState fluidState = level.getFluidState(pos);
                boolean waterCell = fluidState.getType() == Fluids.WATER
                        || fluidState.getType() == Fluids.FLOWING_WATER;
                var stateShape = state.getCollisionShape(level, pos);
                var aboveShape = above.getCollisionShape(level, pos.above());
                var belowShape = below.getCollisionShape(level, pos.below());
                double offset = NavigationGeometry.feetOffset(stateShape, belowShape);
                GridPos gridPos = new GridPos(x, y, z);
                knownCells.add(gridPos);
                if (offset != 0.0D) surfaceOffsets.put(gridPos, offset);
                var planningState = NavigationGeometry.planningState(state, observer.mayInteract(level, pos));
                var planningShape = planningState.getCollisionShape(level, pos);
                if (!planningShape.isEmpty()) {
                    collisions.put(gridPos, planningShape.toAabbs().stream().map(box -> box.move(pos)).toList());
                }
                boolean clearBody = clearsCenteredBody(state, stateShape, bodyWidth, 1.0D);
                boolean standingClearance = hasClearanceAbove(above, aboveShape, standingHeight, bodyWidth, offset);
                boolean crouchingClearance = hasClearanceAbove(above, aboveShape, crouchingHeight, bodyWidth, offset);
                if (offset != 0.0D) {
                    standingClearance = NavigationGeometry.bodyFits(level, pos, offset, bodyWidth,
                            standingHeight + STANDING_CLEARANCE_MARGIN, false);
                    crouchingClearance = NavigationGeometry.bodyFits(level, pos, offset, bodyWidth,
                            crouchingHeight + CROUCHING_CLEARANCE_MARGIN, false);
                }
                boolean standingBody = clearBody && standingClearance;
                boolean crouchingBody = clearBody && crouchingClearance;
                // A bottom slab, carpet or similar partial block is a valid cell
                // under the player's feet. Full-height stairs/fences remain solid.
                boolean lowSurface = !stateShape.isEmpty() && stateShape.max(Direction.Axis.Y) <= 0.5D
                        && (standingClearance || crouchingClearance);
                boolean climbable = state.is(BlockTags.CLIMBABLE)
                        && (aboveShape.isEmpty() || above.is(BlockTags.CLIMBABLE));
                if (climbable) climbableCells.add(new GridPos(x, y, z));
                boolean openable = (isClosedHandOpenableDoor(state) || isClosedHandOpenableTrapdoor(state)
                        || isClosedFenceGate(state))
                        && observer.mayInteract(level, pos);
                boolean openGate = state.getBlock() instanceof FenceGateBlock
                        && state.hasProperty(FenceGateBlock.OPEN) && state.getValue(FenceGateBlock.OPEN);
                FluidState belowFluid = level.getFluidState(pos.below());
                boolean belowWater = belowFluid.getType() == Fluids.WATER
                        || belowFluid.getType() == Fluids.FLOWING_WATER;
                boolean lavaCell = fluidState.getType() == Fluids.LAVA
                        || fluidState.getType() == Fluids.FLOWING_LAVA;
                boolean fluidAllowed = (!waterCell && !lavaCell) || (allowWater && waterCell);
                boolean floor = below.getCollisionShape(level, pos.below()).isEmpty()
                        ? (allowWater && belowWater)
                        : true;
                if (clearBody && (standingClearance || crouchingClearance)) freeBody.add(gridPos);
                boolean breakableBody = allowBreaking && !clearBody && hasUsableTool(observer, state)
                        && isBreakable(level, pos, state)
                        && (above.getCollisionShape(level, pos.above()).isEmpty()
                            || (hasUsableTool(observer, above) && isBreakable(level, pos.above(), above)))
                        && !below.getCollisionShape(level, pos.below()).isEmpty();
                boolean breakableAbove = allowBreaking && clearBody && !standingClearance && !crouchingClearance
                        && hasUsableTool(observer, above) && isBreakable(level, pos.above(), above)
                        && !below.getCollisionShape(level, pos.below()).isEmpty();
                boolean supportedPlacement = allowPlacing && availablePlacements > 0 && (standingBody || crouchingBody)
                        && below.getCollisionShape(level, pos.below()).isEmpty()
                        && (hasSolidNeighbour(level, pos.below()) || hasSolidNeighbour(level, pos));
                boolean passableCell = fluidAllowed && (((standingBody || crouchingBody) && floor)
                        || lowSurface || climbable || openable || openGate
                        || breakableBody || breakableAbove || supportedPlacement);
                // A player can stand on the edge of an adjacent block while
                // its position falls inside an unsupported grid cell. The
                // actual grounded player is a valid search start, even when
                // that cell would not be a safe destination for a path.
                boolean groundedStart = gridPos.equals(start) && observer.onGround()
                        && fluidAllowed && (standingBody || crouchingBody);
                if (passableCell || groundedStart
                        || (allowWater && waterCell && (standingClearance || crouchingClearance))) {
                    passable.add(gridPos);
                }
                if (breakableBody) {
                    actionCosts.merge(gridPos, breakCost(observer, level, pos, state), Math::max);
                    if (!above.getCollisionShape(level, pos.above()).isEmpty()) {
                        actionCosts.merge(gridPos, breakCost(observer, level, pos.above(), above), Math::max);
                    }
                } else if (breakableAbove) {
                    actionCosts.merge(gridPos, breakCost(observer, level, pos.above(), above), Math::max);
                } else if (supportedPlacement) {
                    // Placing is deliberately more expensive than a normal step;
                    // it is a fallback for gaps, not a preferred flat-ground move.
                    actionCosts.merge(gridPos, 8.0D, Math::max);
                    if (!floor && !climbable && !openable && !openGate) placementCells.add(gridPos);
                }
                if (passable.contains(gridPos) && !standingClearance && crouchingClearance
                        && (crouchingBody || lowSurface)) crouchOnly.add(gridPos);
                if (waterCell) waterCells.add(gridPos);
                boolean dangerousSupport = below.is(Blocks.FIRE) || below.is(Blocks.SOUL_FIRE)
                        || below.is(Blocks.CAMPFIRE) || below.is(Blocks.SOUL_CAMPFIRE)
                        || below.is(Blocks.MAGMA_BLOCK) || below.is(Blocks.CACTUS)
                        || below.is(Blocks.POWDER_SNOW) || belowFluid.getType() == Fluids.LAVA
                        || belowFluid.getType() == Fluids.FLOWING_LAVA;
                if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CAMPFIRE)
                        || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.MAGMA_BLOCK)
                        || state.is(Blocks.CACTUS) || state.is(Blocks.POWDER_SNOW)
                        || lavaCell || dangerousSupport) {
                    dangerous.add(gridPos);
                }
            }
            if (x <= maxX) return false;
            // Living entities are dynamic hazards. Capture their current cells and
            // immediate neighbours so A* prefers a short detour without touching
            // entities from the worker thread later.
            AABB scan = new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, scan)) {
                if (entity == observer || !entity.isAlive() || entity.isRemoved() || entity.isSpectator()) continue;
                BlockPos entityPos = entity.blockPosition();
                GridPos occupied = new GridPos(entityPos.getX(), entityPos.getY(), entityPos.getZ());
                // The requested position can be occupied by the player who
                // issued a "come" command. Keep that geometrically walkable
                // cell as the planning endpoint. Live collision checks still
                // prevent the bot from walking into the entity, and arrival
                // tolerance normally completes the task from a neighbour.
                if (!occupied.equals(start) && !occupied.equals(destination)) passable.remove(occupied);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    GridPos hazard = new GridPos(entityPos.getX() + dx, entityPos.getY(), entityPos.getZ() + dz);
                    if (hazard.x() >= minX && hazard.x() <= maxX && hazard.y() >= minY && hazard.y() <= maxY
                            && hazard.z() >= minZ && hazard.z() <= maxZ) dangerous.add(hazard);
                }
            }
            result = new WorldSnapshot(passable, dangerous, crouchOnly,
                    climbableCells, waterCells, freeBody, actionCosts,
                    surfaceOffsets, collisions, knownCells, bodyWidth, standingHeight, placementCells, availablePlacements,
                    minX, maxX, minY, maxY, minZ, maxZ, hasUnloadedChunks);
            return true;
        }

        WorldSnapshot result() {
            if (result == null) throw new IllegalStateException("Snapshot capture is incomplete");
            return result;
        }
    }

    private static boolean hasClearanceAbove(BlockState above, VoxelShape aboveShape, double height, double width,
                                            double surfaceOffset) {
        double margin = height <= 1.6D ? CROUCHING_CLEARANCE_MARGIN : STANDING_CLEARANCE_MARGIN;
        double needed = height + surfaceOffset + margin - 1.0D;
        return needed <= 0.0D || aboveShape.isEmpty()
                || clearsCenteredBody(above, aboveShape, width, needed)
                || aboveShape.min(Direction.Axis.Y) >= needed;
    }

    private static boolean clearsCenteredBody(BlockState state, VoxelShape shape, double width, double height) {
        if (shape.isEmpty()) return true;
        if (!(state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock)
                || !state.hasProperty(DoorBlock.OPEN) || !state.getValue(DoorBlock.OPEN)) return false;
        double halfWidth = width / 2.0D;
        AABB body = new AABB(0.5D - halfWidth, 0.0D, 0.5D - halfWidth,
                0.5D + halfWidth, height, 0.5D + halfWidth);
        for (AABB part : shape.toAabbs()) if (part.intersects(body)) return false;
        return true;
    }

    private static boolean isClosedHandOpenableDoor(net.minecraft.world.level.block.state.BlockState state) {
        return state.getBlock() instanceof DoorBlock door
                // Copper doors are not in WOODEN_DOORS, but their block set
                // explicitly allows the same hand interaction as wood doors.
                && door.type().canOpenByHand()
                && state.hasProperty(DoorBlock.OPEN) && !state.getValue(DoorBlock.OPEN)
                && (!state.hasProperty(DoorBlock.HALF)
                    || state.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
    }

    private static boolean isClosedHandOpenableTrapdoor(net.minecraft.world.level.block.state.BlockState state) {
        return PassageBlocks.handOpenableTrapdoor(state)
                && state.hasProperty(TrapDoorBlock.OPEN) && !state.getValue(TrapDoorBlock.OPEN);
    }

    private static boolean isClosedFenceGate(net.minecraft.world.level.block.state.BlockState state) {
        return state.getBlock() instanceof FenceGateBlock
                && state.hasProperty(FenceGateBlock.OPEN)
                && !state.getValue(FenceGateBlock.OPEN);
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

    private static boolean hasUsableTool(ServerPlayer player, net.minecraft.world.level.block.state.BlockState state) {
        if (!state.requiresCorrectToolForDrops()) return true;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || !stack.isCorrectToolForDrops(state) || stack.nextDamageWillBreak()) continue;
            if (effectiveDestroySpeed(stack, state) > 0.0f) return true;
        }
        return false;
    }

    private static double breakCost(ServerPlayer player, ServerLevel level, BlockPos pos,
                                    net.minecraft.world.level.block.state.BlockState state) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0f) return Double.POSITIVE_INFINITY;
        float bestSpeed = 1.0f;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || stack.nextDamageWillBreak()) continue;
            if (state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)) continue;
            bestSpeed = Math.max(bestSpeed, effectiveDestroySpeed(stack, state));
        }
        // This is a planning estimate in ticks. MinerImpl remains the
        // authority for the actual vanilla destroy progress.
        return Math.max(2.0D, hardness * 30.0D / bestSpeed);
    }

    /** Includes the vanilla Efficiency contribution in planning estimates. */
    private static float effectiveDestroySpeed(net.minecraft.world.item.ItemStack stack,
                                               net.minecraft.world.level.block.state.BlockState state) {
        float speed = stack.getDestroySpeed(state);
        if (speed <= 0.0f) return speed;
        int efficiency = 0;
        for (var entry : stack.getEnchantments().entrySet()) {
            if (entry.getKey().is(Enchantments.EFFICIENCY)) {
                efficiency = Math.max(efficiency, entry.getIntValue());
            }
        }
        return efficiency > 0 ? speed + efficiency * efficiency + 1.0f : speed;
    }

    @Override public boolean passable(GridPos pos) {
        return pos.x() >= minX && pos.x() <= maxX && pos.y() >= minY && pos.y() <= maxY && pos.z() >= minZ && pos.z() <= maxZ && passable.contains(pos);
    }
    boolean hasUnloadedChunks() { return hasUnloadedChunks; }
    @Override public boolean canTraverse(GridPos from, GridPos to) {
        if (!GridWorld.super.canTraverse(from, to)) return false;
        int dx = Math.abs(to.x() - from.x());
        int dz = Math.abs(to.z() - from.z());
        // A horizontal one-block rise is executed with the jump input. It
        // needs the same standing-height arc clearance as a gap jump; a
        // crouch-only cell is walkable but cannot be jumped through.
        if (to.y() == from.y() + 1 && dx + dz == 1) {
            return !requiresSneak(from) && !requiresSneak(to)
                    && freeBody.contains(from.add(0, 1, 0))
                    && freeBody.contains(to.add(0, 1, 0));
        }
        return true;
    }
    @Override public boolean dangerous(GridPos pos) { return dangerous.contains(pos); }
    @Override public boolean canJump(GridPos from, GridPos to) {
        if (!passable(from) || !passable(to) || from.y() != to.y()) return false;
        // Climbable blocks are not landing platforms for long sprint jumps.
        // Enter them with short walking transitions before climbing.
        if (climbableCells.contains(from) || climbableCells.contains(to)
                || requiresSneak(from) || requiresSneak(to) || isWater(from) || isWater(to)) return false;
        return NavigationGeometry.clearJump(feetPoint(from), feetPoint(to), bodyWidth, standingHeight,
                this::collisionFree, pos -> dangerous.contains(new GridPos(pos.getX(), pos.getY(), pos.getZ()))
                        || waterCells.contains(new GridPos(pos.getX(), pos.getY(), pos.getZ())));
    }

    private net.minecraft.world.phys.Vec3 feetPoint(GridPos pos) {
        return new net.minecraft.world.phys.Vec3(pos.x() + 0.5D,
                pos.y() + surfaceOffsets.getOrDefault(pos, 0.0D), pos.z() + 0.5D);
    }

    private boolean collisionFree(AABB box) {
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY - 1, box.minZ),
                BlockPos.containing(box.maxX - 1.0E-6D, box.maxY - 1.0E-6D, box.maxZ - 1.0E-6D))) {
            GridPos cell = new GridPos(pos.getX(), pos.getY(), pos.getZ());
            if (!knownCells.contains(cell)) return false;
            for (AABB part : collisions.getOrDefault(cell, List.of())) if (part.intersects(box)) return false;
        }
        return true;
    }
    @Override public boolean canDrop(GridPos from, GridPos to) {
        if (!passable(from) || !passable(to) || to.y() >= from.y()) return false;
        int dx = Math.abs(to.x() - from.x());
        int dz = Math.abs(to.z() - from.z());
        int distance = from.y() - to.y();
        if (dx + dz != 1 || distance < 2 || distance > 16) return false;
        // The body falls through the target column. Every intermediate cell
        // must have enough clearance and must not be dangerous. A solid wall,
        // disallowed water, or lava in the column invalidates the drop even
        // if the landing cell itself is walkable.
        for (int y = to.y() + 1; y <= from.y(); y++) {
            GridPos intermediate = new GridPos(to.x(), y, to.z());
            if (!freeBody.contains(intermediate) || dangerous.contains(intermediate)
                    || (waterCells.contains(intermediate) && !passable(intermediate))) return false;
        }
        return true;
    }
    @Override public boolean requiresSneak(GridPos pos) { return crouchOnly.contains(pos); }
    @Override public boolean isWater(GridPos pos) { return waterCells.contains(pos); }
    @Override public boolean canDescendWithoutFalling(GridPos from, GridPos to) {
        return climbableCells.contains(from) || climbableCells.contains(to)
                || (waterCells.contains(from) && waterCells.contains(to));
    }
    @Override public boolean requiresPlacement(GridPos pos) { return placementCells.contains(pos); }
    @Override public int maxPlacements() { return availablePlacements; }
    @Override public int maxWaterSteps() { return Integer.MAX_VALUE; }
    @Override public double cost(GridPos from, GridPos to) {
        double base = GridWorld.super.cost(from, to);
        return base + (waterCells.contains(to) ? WATER_STEP_COST : 0.0D)
                + (crouchOnly.contains(to) ? 0.25D : 0.0D)
                + actionCosts.getOrDefault(to, 0.0D);
    }
}
