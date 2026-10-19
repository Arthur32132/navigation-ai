package dev.navigationai.runtime;

import dev.navigationai.navigation.GridPos;
import dev.navigationai.navigation.GridWorld;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
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
    private Set<GridPos> openableCells = Set.of(), breakingCells = Set.of();
    private Set<GridPos> entityBlocked = Set.of(), entityDanger = Set.of(), entityOccupied = Set.of();
    private List<AABB> entityCollisions = List.of();
    private Map<Long, ChunkGeometryStamp> chunkStamps = Map.of();
    private ServerLevel capturedLevel;
    private boolean capturedWater, capturedBreaking, capturedPlacing;
    private long reusedCells;
    private boolean sprintAvailable = true;
    private GridPos capturedStart;
    private Set<GridPos> groundedOnlyCells = Set.of();
    private double crouchingHeight = 1.5;

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

    static WorldSnapshot capture(ServerLevel level, LivingEntity observer, GridPos start, int radius, boolean allowWater,
                                 boolean allowChunkGeneration, boolean allowBreaking, boolean allowPlacing,
                                 BooleanSupplier generationBudget) {
        Capture capture = beginCapture(level, observer, start, radius, allowWater,
                allowChunkGeneration, allowBreaking, allowPlacing, generationBudget);
        while (!capture.step(Integer.MAX_VALUE)) { }
        return capture.result();
    }

    static Capture beginCapture(ServerLevel level, LivingEntity observer, GridPos start, int radius,
                                boolean allowWater, boolean allowChunkGeneration, boolean allowBreaking,
                                boolean allowPlacing, BooleanSupplier generationBudget) {
        return beginCapture(level, observer, start, null, radius, allowWater,
                allowChunkGeneration, allowBreaking, allowPlacing, generationBudget);
    }

    static Capture beginCapture(ServerLevel level, LivingEntity observer, GridPos start, GridPos destination,
                                int radius, boolean allowWater, boolean allowChunkGeneration,
                                boolean allowBreaking, boolean allowPlacing, BooleanSupplier generationBudget) {
        return new Capture(level, observer, start, radius, allowWater,
                allowChunkGeneration, allowBreaking, allowPlacing, generationBudget, destination);
    }

    static Capture beginCapture(ServerLevel level, LivingEntity observer, GridPos start, GridPos destination,
                                int radius, boolean allowWater, boolean allowChunkGeneration,
                                boolean allowBreaking, boolean allowPlacing, BooleanSupplier generationBudget,
                                WorldSnapshot previous) {
        Capture capture = beginCapture(level, observer, start, destination, radius, allowWater, allowChunkGeneration,
                allowBreaking, allowPlacing, generationBudget);
        if (previous != null && previous.reusable(level, observer, allowWater, allowBreaking, allowPlacing)) {
            capture.previous = previous;
        }
        return capture;
    }

    private boolean reusable(ServerLevel level, LivingEntity observer, boolean water, boolean breaking, boolean placing) {
        // Tool/inventory and protection changes are checked by fresh captures for transactional routes.
        return capturedLevel == level && capturedWater == water && !breaking && !placing
                && !capturedBreaking && !capturedPlacing
                && bodyWidth == observer.getDimensions(Pose.STANDING).width()
                && standingHeight == observer.getDimensions(Pose.STANDING).height()
                && crouchingHeight == observer.getDimensions(Pose.CROUCHING).height();
    }

    WorldSnapshot reuseIfCurrent(ServerLevel level, LivingEntity observer, GridPos start, GridPos destination,
                                 boolean water, boolean breaking, boolean placing) {
        if (!reusable(level, observer, water, breaking, placing)
                || start.x() < minX + 4 || start.x() > maxX - 4 || start.z() < minZ + 4 || start.z() > maxZ - 4
                || start.y() < minY + 3 || start.y() > maxY - 3 || !geometryCurrent(level)) return null;
        WorldSnapshot copy = new WorldSnapshot(passable, dangerous, crouchOnly, climbableCells, waterCells, freeBody,
                actionCosts, surfaceOffsets, collisions, knownCells, bodyWidth, standingHeight, placementCells,
                availablePlacements, minX, maxX, minY, maxY, minZ, maxZ, hasUnloadedChunks);
        copy.openableCells = openableCells; copy.breakingCells = breakingCells; copy.chunkStamps = chunkStamps;
        if (sprintAvailable != (ActorSupport.canSprint(observer))) return null;
        copy.capturedLevel = level; copy.capturedWater = water; copy.reusedCells = knownCells.size();
        copy.sprintAvailable = sprintAvailable; copy.capturedStart = start; copy.groundedOnlyCells = groundedOnlyCells;
        copy.crouchingHeight = crouchingHeight;
        copy.captureEntities(level, observer, start, destination);
        // A new grounded start can straddle an edge that was not a supported cell in the old capture.
        // Capture it afresh rather than submitting an empty search from stale start classification.
        if (!copy.passable(start)) return null;
        return copy;
    }

    boolean geometryCurrent(ServerLevel level) {
        return capturedLevel == level && chunkStamps.entrySet().stream().allMatch(entry ->
                entry.getValue().current(level, (int) (entry.getKey() >> 32), (int) (long) entry.getKey()));
    }

    long reusedCells() { return reusedCells; }
    record RouteRevision(ServerLevel level, Map<Long, ChunkGeometryStamp> chunks) {
        boolean current() {
            return chunks.entrySet().stream().allMatch(entry -> entry.getValue().current(level,
                    (int)(entry.getKey() >> 32), (int)(long)entry.getKey()));
        }
    }
    RouteRevision routeRevision() { return new RouteRevision(capturedLevel, chunkStamps); }
    boolean sprintAvailable() { return sprintAvailable; }
    boolean occupiedByEntity(GridPos pos) { return entityOccupied.contains(pos); }
    boolean known(GridPos pos) { return knownCells.contains(pos); }
    private static long chunkKey(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    static boolean loadedGeometryAt(ServerLevel level, int blockX, int blockZ) {
        // hasChunk confirms a requested ticket level, including pending FULL
        // futures. Collision shapes can also read neighbouring blocks.
        var source = level.getChunkSource();
        for (int cx = (blockX - 1) >> 4; cx <= (blockX + 1) >> 4; cx++) {
            for (int cz = (blockZ - 1) >> 4; cz <= (blockZ + 1) >> 4; cz++) {
                if (source.getChunkNow(cx, cz) == null) return false;
            }
        }
        return true;
    }

    /** Small live view uses exactly the same classification and geometry rules as the planner. */
    static WorldSnapshot transitionView(ServerLevel level, LivingEntity observer, GridPos from, GridPos to,
                                         boolean water, boolean breaking, boolean placing) {
        Capture capture = beginTransitionView(level, observer, from, to, water, breaking, placing);
        while (!capture.step(Integer.MAX_VALUE)) { }
        return finishTransitionView(capture);
    }

    static Capture beginTransitionView(ServerLevel level, LivingEntity observer, GridPos from, GridPos to,
                                       boolean water, boolean breaking, boolean placing) {
        Capture capture = new Capture(level, observer, from, 1, water, false, breaking, placing, null, to);
        capture.minX = Math.min(from.x(), to.x()) - 1; capture.maxX = Math.max(from.x(), to.x()) + 1;
        capture.minZ = Math.min(from.z(), to.z()) - 1; capture.maxZ = Math.max(from.z(), to.z()) + 1;
        capture.minY = Math.min(from.y(), to.y()) - 2; capture.maxY = Math.max(from.y(), to.y()) + 4;
        capture.x = capture.minX; capture.y = capture.minY; capture.z = capture.minZ;
        return capture;
    }

    static WorldSnapshot finishTransitionView(Capture capture) {
        WorldSnapshot result = capture.result();
        result.entityBlocked = Set.of(); result.entityDanger = Set.of();
        result.entityCollisions = List.of();
        return result;
    }

    private void captureEntities(ServerLevel level, LivingEntity observer, GridPos start, GridPos destination) {
        // Bit sets over the snapshot box: passable() asks about entities for every cell a search visits.
        var box = CellSet.CellBox.of(minX - 1, maxX + 1, minY - 1, maxY + 2, minZ - 1, maxZ + 1);
        Set<GridPos> blocked = new CellSet(box), hazards = new CellSet(box), occupiedCells = new CellSet(box);
        List<AABB> entityBodies = new ArrayList<>();
        AABB scan = new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, scan)) {
            if (entity == observer || !entity.isAlive() || entity.isRemoved() || entity.isSpectator()) continue;
            entityBodies.add(entity.getBoundingBox().inflate(.10));
            BlockPos pos = entity.blockPosition();
            GridPos occupied = new GridPos(pos.getX(), pos.getY(), pos.getZ());
            occupiedCells.add(occupied);
            if (!occupied.equals(start) && !occupied.equals(destination)) blocked.add(occupied);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) hazards.add(occupied.add(dx, 0, dz));
        }
        entityBlocked = blocked; entityDanger = hazards; entityOccupied = occupiedCells;
        entityCollisions = List.copyOf(entityBodies);
    }

    /** Scans a bounded number of cells per server tick before publishing an immutable snapshot. */
    static final class Capture {
        private final ServerLevel level;
        private final LivingEntity observer;
        private final GridPos start;
        private final GridPos destination;
        private final boolean allowWater, allowBreaking, allowPlacing;
        private int minX, maxX, minY, maxY, minZ, maxZ;
        // Dense per-cell storage over the capture box: searches query these sets for every node.
        private final Set<GridPos> passable, dangerous, crouchOnly, climbableCells, waterCells, freeBody;
        private final Map<GridPos, Double> actionCosts, surfaceOffsets;
        private final Map<GridPos, List<AABB>> collisions;
        private final Set<GridPos> knownCells, placementCells, openableCells, breakingCells;
        private final Map<Long, ChunkGeometryStamp> chunkStamps = new HashMap<>();
        private WorldSnapshot previous;
        private long reusedCells;
        private final Set<GridPos> groundedOnlyCells;
        private final int availablePlacements;
        private final double standingHeight, crouchingHeight, bodyWidth;
        private int x, y, z;
        private boolean hasUnloadedChunks;
        private WorldSnapshot result;
        // The column being scanned: whether its chunks are loaded and its geometry stamp. Reset at
        // every step, since a chunk can unload between ticks.
        private int columnX, columnZ;
        private boolean columnLoaded, columnReusable;
        private long columnKey;
        // The scan walks each column upwards, so the cell below is the previous cell and this cell
        // was the previous cell's "above": one block lookup per cell instead of three.
        private int windowY;
        private BlockState windowState, windowAbove;
        private VoxelShape windowStateShape, windowAboveShape;

        private Capture(ServerLevel level, LivingEntity observer, GridPos start, int radius, boolean allowWater,
                        boolean allowChunkGeneration, boolean allowBreaking, boolean allowPlacing,
                        BooleanSupplier generationBudget, GridPos destination) {
            this.level = level;
            this.observer = observer;
            this.start = start;
            this.destination = destination;
            this.allowWater = allowWater;
            this.allowBreaking = allowBreaking;
            this.allowPlacing = allowPlacing;
            this.availablePlacements = allowPlacing ? NavigationSafety.availableBuildingBlocks(observer, level) : 0;
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
            // One spare layer above and below: collision sweeps also ask about the cells next to the box.
            var box = CellSet.CellBox.of(minX - 1, maxX + 1, minY - 1, maxY + 2, minZ - 1, maxZ + 1);
            passable = new CellSet(box); dangerous = new CellSet(box); crouchOnly = new CellSet(box);
            climbableCells = new CellSet(box); waterCells = new CellSet(box); freeBody = new CellSet(box);
            knownCells = new CellSet(box); placementCells = new CellSet(box); openableCells = new CellSet(box);
            breakingCells = new CellSet(box); groundedOnlyCells = new CellSet(box);
            actionCosts = new CellMap<>(box); surfaceOffsets = new CellMap<>(box); collisions = new CellMap<>(box);
        }

        boolean step(int maxCells) { return step(maxCells, Long.MAX_VALUE); }

        boolean step(int maxCells, long deadlineNanos) {
            if (result != null) return true;
            int processed = 0;
            columnX = Integer.MIN_VALUE; windowY = Integer.MIN_VALUE;
            while (x <= maxX && processed++ < maxCells) {
                if (System.nanoTime() >= deadlineNanos) return false;
                int x = this.x, y = this.y, z = this.z;
                if (++this.y > maxY) {
                    this.y = minY;
                    if (++this.z > maxZ) { this.z = minZ; this.x++; }
                }
                if (x != columnX || z != columnZ) {
                    columnX = x; columnZ = z; windowY = Integer.MIN_VALUE;
                    // Navigation tickets request generation asynchronously. Never
                    // wait for chunk generation inside the server tick capture.
                    columnLoaded = loadedGeometryAt(level, x, z);
                    if (columnLoaded) {
                        columnKey = chunkKey(x >> 4, z >> 4);
                        ChunkGeometryStamp stamp = chunkStamps.computeIfAbsent(columnKey,
                                ignored -> ChunkGeometryStamp.read(level, x >> 4, z >> 4));
                        columnReusable = previous != null && stamp.equals(previous.chunkStamps.get(columnKey));
                    }
                }
                if (!columnLoaded) {
                    hasUnloadedChunks = true;
                    continue;
                }
                BlockPos pos = new BlockPos(x, y, z);
                GridPos gridPos = new GridPos(x, y, z);
                if (columnReusable && previous.knownCells.contains(gridPos)) {
                    copyCell(previous, gridPos);
                    reusedCells++;
                    windowY = Integer.MIN_VALUE;
                    continue;
                }
                BlockState state, below;
                VoxelShape stateShape, belowShape;
                if (windowY == y - 1) {
                    below = windowState; belowShape = windowStateShape;
                    state = windowAbove; stateShape = windowAboveShape;
                } else {
                    below = level.getBlockState(pos.below()); belowShape = below.getCollisionShape(level, pos.below());
                    state = level.getBlockState(pos); stateShape = state.getCollisionShape(level, pos);
                }
                var above = level.getBlockState(pos.above());
                var aboveShape = above.getCollisionShape(level, pos.above());
                windowY = y; windowState = state; windowStateShape = stateShape;
                windowAbove = above; windowAboveShape = aboveShape;
                // A block's fluid is part of its state; level.getFluidState would look the block up again.
                FluidState fluidState = state.getFluidState();
                FluidState belowFluid = below.getFluidState();
                boolean belowWater = belowFluid.getType() == Fluids.WATER
                        || belowFluid.getType() == Fluids.FLOWING_WATER;
                boolean waterCell = fluidState.getType() == Fluids.WATER
                        || fluidState.getType() == Fluids.FLOWING_WATER;
                double offset = NavigationGeometry.feetOffset(stateShape, belowShape);
                boolean waterSurface = fluidState.isEmpty() && belowWater && stateShape.isEmpty() && belowShape.isEmpty();
                if (waterSurface) offset = belowFluid.getHeight(level, pos.below()) - 1.0;
                knownCells.add(gridPos);
                if (offset != 0.0D) surfaceOffsets.put(gridPos, offset);
                // Only doors, trapdoors and gates depend on the actor's permission to use them.
                boolean passage = state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock
                        || state.getBlock() instanceof FenceGateBlock;
                boolean mayOpen = passage && ActorSupport.mayInteract(observer, level, pos);
                var planningState = NavigationGeometry.planningState(state, mayOpen);
                var planningShape = planningState == state ? stateShape : planningState.getCollisionShape(level, pos);
                if (!planningShape.isEmpty()) {
                    collisions.put(gridPos, planningShape.toAabbs().stream().map(box -> box.move(pos)).toList());
                }
                boolean clearBody = clearsCenteredBody(state, stateShape, bodyWidth, 1.0D);
                boolean standingClearance = hasClearanceAbove(above, aboveShape, standingHeight, bodyWidth, offset);
                boolean crouchingClearance = hasClearanceAbove(above, aboveShape, crouchingHeight, bodyWidth, offset);
                if (offset != 0.0D) {
                    standingClearance = NavigationGeometry.bodyFits(level, pos, offset, bodyWidth,
                            standingHeight + NavigationGeometry.clearanceMargin(standingHeight,false), false);
                    crouchingClearance = NavigationGeometry.bodyFits(level, pos, offset, bodyWidth,
                            crouchingHeight + NavigationGeometry.clearanceMargin(crouchingHeight,true), false);
                }
                boolean standingBody = clearBody && standingClearance;
                boolean crouchingBody = clearBody && crouchingClearance;
                // Slabs, farmland, paths and other partial blocks are valid cells
                // under the player's feet. Full-height stairs/fences remain solid.
                boolean lowSurface = NavigationGeometry.partialSupport(stateShape)
                        && (standingClearance || crouchingClearance);
                boolean climbable = state.is(BlockTags.CLIMBABLE)
                        && (aboveShape.isEmpty() || above.is(BlockTags.CLIMBABLE));
                if (climbable) climbableCells.add(new GridPos(x, y, z));
                boolean openable = (isClosedHandOpenableDoor(state) || isClosedHandOpenableTrapdoor(state)
                        || isClosedFenceGate(state)) && mayOpen;
                if (openable) openableCells.add(gridPos);
                boolean openGate = state.getBlock() instanceof FenceGateBlock
                        && state.hasProperty(FenceGateBlock.OPEN) && state.getValue(FenceGateBlock.OPEN);
                boolean lavaCell = fluidState.getType() == Fluids.LAVA
                        || fluidState.getType() == Fluids.FLOWING_LAVA;
                boolean fluidAllowed = (!waterCell && !lavaCell) || (allowWater && waterCell);
                boolean floor = belowShape.isEmpty()
                        ? (allowWater && belowWater)
                        : true;
                boolean jumpClearBody=clearBody,jumpClearance=standingClearance||crouchingClearance;
                if(state.getBlock() instanceof DoorBlock||above.getBlock() instanceof DoorBlock){
                    // The opening action clears BOTH halves before a raised doorway jump.
                    // This is clearance only: the upper half is not a landing platform.
                    var openedAbove=NavigationGeometry.planningState(above,ActorSupport.mayInteract(observer,level,pos.above()));
                    var openedAboveShape=openedAbove.getCollisionShape(level,pos.above());
                    jumpClearBody=clearsCenteredBody(planningState,planningShape,bodyWidth,1.0D);
                    jumpClearance=hasClearanceAbove(openedAbove,openedAboveShape,standingHeight,bodyWidth,offset)
                            ||hasClearanceAbove(openedAbove,openedAboveShape,crouchingHeight,bodyWidth,offset);
                }
                if (jumpClearBody && jumpClearance) freeBody.add(gridPos);
                boolean breakableBody = allowBreaking && !clearBody && hasUsableTool(observer, state)
                        && isBreakable(level, pos, state)&&ActorSupport.mayBreak(observer,level,pos)
                        && (aboveShape.isEmpty()
                            || (hasUsableTool(observer, above) && isBreakable(level, pos.above(), above)&&ActorSupport.mayBreak(observer,level,pos.above())))
                        && !belowShape.isEmpty();
                boolean breakableAbove = allowBreaking && clearBody && !standingClearance && !crouchingClearance
                        && hasUsableTool(observer, above) && isBreakable(level, pos.above(), above)
                        &&ActorSupport.mayBreak(observer,level,pos.above())
                        && !belowShape.isEmpty();
                if (breakableBody || breakableAbove) breakingCells.add(gridPos);
                // A previous route step may place the neighbour that supports this block.
                // Keep these virtual cells local to the current bridge level; execution
                // still verifies the real neighbour immediately before each placement.
                boolean supportedPlacement = allowPlacing && availablePlacements > 0 && (standingBody || crouchingBody)
                        && Math.abs(y - start.y()) <= 1 && below.canBeReplaced()
                        && belowShape.isEmpty() && belowFluid.isEmpty();
                boolean passableCell = fluidAllowed && (((standingBody || crouchingBody) && floor)
                        || lowSurface || climbable || openable || openGate
                        || breakableBody || breakableAbove || supportedPlacement);
                // A player can stand on the edge of an adjacent block while
                // its position falls inside an unsupported grid cell. The
                // actual grounded player is a valid search start, even when
                // that cell would not be a safe destination for a path.
                boolean groundedStart = gridPos.equals(start) && observer.onGround()
                        && fluidAllowed && (standingBody || crouchingBody);
                if (!passableCell && groundedStart) groundedOnlyCells.add(gridPos);
                if (passableCell || groundedStart
                        || (allowWater && waterCell && (standingClearance || crouchingClearance))) {
                    passable.add(gridPos);
                }
                if (breakableBody) {
                    actionCosts.merge(gridPos, breakCost(observer, level, pos, state), Math::max);
                    if (!aboveShape.isEmpty()) {
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
                if ((clearBody || lowSurface) && !standingClearance && crouchingClearance) crouchOnly.add(gridPos);
                if (waterCell || waterSurface) waterCells.add(gridPos);
                // Prefer a breathable surface or a longer bank route to a submerged tunnel.
                // Diving remains possible when the destination actually requires it.
                if(waterCell){
                    var headFluid=above.getFluidState();
                    double eyeAboveHeadFloor=observer.getEyeHeight()+offset-1;
                    if((headFluid.getType()==Fluids.WATER||headFluid.getType()==Fluids.FLOWING_WATER)
                            &&headFluid.getHeight(level,pos.above())>eyeAboveHeadFloor)
                        actionCosts.merge(gridPos,20.0D,Double::sum);
                }
                boolean dangerousSupport = NavigationSafety.dangerous(below) || belowFluid.getType() == Fluids.LAVA
                        || belowFluid.getType() == Fluids.FLOWING_LAVA;
                if (NavigationSafety.dangerous(state) || lavaCell || dangerousSupport) {
                    dangerous.add(gridPos);
                }
            }
            if (x <= maxX) return false;
            result = new WorldSnapshot(passable, dangerous, crouchOnly,
                    climbableCells, waterCells, freeBody, actionCosts,
                    surfaceOffsets, collisions, knownCells, bodyWidth, standingHeight, placementCells, availablePlacements,
                    minX, maxX, minY, maxY, minZ, maxZ, hasUnloadedChunks);
            result.openableCells = openableCells; result.breakingCells = breakingCells;
            result.chunkStamps = Map.copyOf(chunkStamps); result.capturedLevel = level;
            result.capturedWater = allowWater; result.capturedBreaking = allowBreaking; result.capturedPlacing = allowPlacing;
            result.reusedCells = reusedCells;
            result.sprintAvailable = ActorSupport.canSprint(observer);
            result.capturedStart = start; result.groundedOnlyCells = groundedOnlyCells;
            result.crouchingHeight = crouchingHeight;
            result.captureEntities(level, observer, start, destination);
            return true;
        }

        private void copyCell(WorldSnapshot source, GridPos pos) {
            knownCells.add(pos);
            if (!source.groundedOnlyCells.contains(pos)) copyFlag(source.passable, passable, pos);
            else if (pos.equals(start) && observer.onGround()) { passable.add(pos); groundedOnlyCells.add(pos); }
            copyFlag(source.dangerous, dangerous, pos);
            copyFlag(source.crouchOnly, crouchOnly, pos); copyFlag(source.climbableCells, climbableCells, pos);
            copyFlag(source.waterCells, waterCells, pos); copyFlag(source.freeBody, freeBody, pos);
            copyFlag(source.openableCells, openableCells, pos); copyFlag(source.breakingCells, breakingCells, pos);
            if (source.actionCosts.containsKey(pos)) actionCosts.put(pos, source.actionCosts.get(pos));
            if (source.surfaceOffsets.containsKey(pos)) surfaceOffsets.put(pos, source.surfaceOffsets.get(pos));
            if (source.collisions.containsKey(pos)) collisions.put(pos, source.collisions.get(pos));
        }

        private static void copyFlag(Set<GridPos> source, Set<GridPos> target, GridPos pos) {
            if (source.contains(pos)) target.add(pos);
        }

        WorldSnapshot result() {
            if (result == null) throw new IllegalStateException("Snapshot capture is incomplete");
            return result;
        }
    }

    private static boolean hasClearanceAbove(BlockState above, VoxelShape aboveShape, double height, double width,
                                            double surfaceOffset) {
        double margin = NavigationGeometry.clearanceMargin(height,height<=1.6);
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

    private static boolean hasUsableTool(LivingEntity player, net.minecraft.world.level.block.state.BlockState state) {
        if (!state.requiresCorrectToolForDrops()) return true;
        for (int slot = 0; slot < ActorSupport.context(player).getInventory().getContainerSize(); slot++) {
            var stack = ActorSupport.context(player).getInventory().getItem(slot);
            if (stack.isEmpty() || !stack.isCorrectToolForDrops(state) || stack.nextDamageWillBreak()) continue;
            if (effectiveDestroySpeed(stack, state) > 0.0f) return true;
        }
        return false;
    }

    private static double breakCost(LivingEntity player, ServerLevel level, BlockPos pos,
                                    net.minecraft.world.level.block.state.BlockState state) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0f) return Double.POSITIVE_INFINITY;
        float bestSpeed = 1.0f;
        for (int slot = 0; slot < ActorSupport.context(player).getInventory().getContainerSize(); slot++) {
            var stack = ActorSupport.context(player).getInventory().getItem(slot);
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
        return pos.x() >= minX && pos.x() <= maxX && pos.y() >= minY && pos.y() <= maxY && pos.z() >= minZ && pos.z() <= maxZ && passable.contains(pos) && !entityBlocked.contains(pos)
                && (!groundedOnlyCells.contains(pos) || pos.equals(capturedStart));
    }
    @Override public boolean isFrontier(GridPos pos) {
        return pos.x() <= minX + 1 || pos.x() >= maxX - 1
                || pos.z() <= minZ + 1 || pos.z() >= maxZ - 1
                || pos.y() <= minY + 1 || pos.y() >= maxY - 1;
    }
    boolean hasUnloadedChunks() { return hasUnloadedChunks; }
    @Override public boolean canTraverse(GridPos from, GridPos to) {
        if (!GridWorld.super.canTraverse(from, to)) return false;
        int dx = Math.abs(to.x() - from.x());
        int dz = Math.abs(to.z() - from.z());
        if (requiresPlacement(to) && (dx + dz != 1 || to.y() != from.y())) return false;
        // A horizontal one-block rise is executed with the jump input. It
        // needs the same standing-height arc clearance as a gap jump; a
        // crouch-only cell is walkable but cannot be jumped through.
        if (supportHeight(to) - supportHeight(from) > .6 && dx + dz == 1) {
            if(requiresSneak(from)||requiresSneak(to))return false;
            // A one-block step needs the body just above its landing height.
            // Checking a full standing body one block ABOVE the landing wrongly
            // rejects ordinary two-block doorways where vanilla safely clips the jump apex.
            double feet=supportHeight(to)+.02;
            double height=standingHeight+NavigationGeometry.clearanceMargin(standingHeight,false);
            var startBody=NavigationGeometry.body(from.x()+.5,feet,from.z()+.5,bodyWidth,height);
            var endBody=NavigationGeometry.body(to.x()+.5,feet,to.z()+.5,bodyWidth,height);
            return collisionFree(startBody.minmax(endBody));
        }
        if (to.y() <= from.y() && dx + dz > 0 && !requiresBreaking(from) && !requiresBreaking(to)
                && !requiresPlacement(from) && !requiresPlacement(to)) {
            // A door can clear the cell centre while its panel blocks an entry or exit face.
            double height = requiresSneak(from) || requiresSneak(to) ? crouchingHeight : standingHeight;
            double feet = Math.max(supportHeight(from), supportHeight(to));
            AABB startBody = NavigationGeometry.body(from.x() + .5, feet, from.z() + .5, bodyWidth, height);
            AABB endBody = NavigationGeometry.body(to.x() + .5, feet, to.z() + .5, bodyWidth, height);
            AABB sweep = startBody.minmax(endBody);
            if (!collisionFree(sweep)) return false;
            // Safe endpoints do not make a diagonal safe: the body can touch a
            // berry bush or cactus in a corner cell that the feet never enter.
            if (!dangerous(from) && !dangerous(to) && dx > 0 && dz > 0) {
                for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(sweep.minX, sweep.minY, sweep.minZ),
                        BlockPos.containing(sweep.maxX - 1.0E-6, sweep.maxY - 1.0E-6, sweep.maxZ - 1.0E-6))) {
                    if (dangerous(new GridPos(pos.getX(), pos.getY(), pos.getZ()))) return false;
                }
            }
        }
        return true;
    }
    @Override public boolean dangerous(GridPos pos) { return dangerous.contains(pos) || entityDanger.contains(pos); }
    @Override public double supportHeight(GridPos pos) { return pos.y() + surfaceOffsets.getOrDefault(pos, 0.0); }
    @Override public boolean isClimbable(GridPos pos) { return climbableCells.contains(pos); }
    @Override public boolean requiresOpening(GridPos pos) { return openableCells.contains(pos); }
    @Override public boolean requiresBreaking(GridPos pos) { return breakingCells.contains(pos); }
    @Override public boolean crossesGap(GridPos from, GridPos to) {
        int samples = (int) Math.ceil(horizontal(from, to) * 8);
        for (int i = 1; i < samples; i++) {
            double t = i / (double) samples;
            GridPos cell = new GridPos((int) Math.floor(from.x() + .5 + (to.x() - from.x()) * t), from.y(),
                    (int) Math.floor(from.z() + .5 + (to.z() - from.z()) * t));
            if (!passable(cell) || requiresPlacement(cell)) return true;
        }
        return false;
    }
    @Override public boolean canJump(GridPos from, GridPos to) {
        if (!passable(from) || !passable(to) || from.y() != to.y()) return false;
        if (requiresPlacement(to)) return false;
        if (!sprintAvailable && horizontal(from, to) > 3.0) return false;
        // Climbable blocks are not landing platforms for long sprint jumps.
        // Enter them with short walking transitions before climbing.
        if (climbableCells.contains(from) || climbableCells.contains(to)
                || requiresSneak(from) || requiresSneak(to) || isWater(from) || isWater(to)) return false;
        // Over plain walkable ground a long jump is never needed: steps reach the same cell more
        // safely. Checking the arc for every direction of every node dominated path search time,
        // and so did sweeping the arc into a tree trunk or a wall that no jump clears.
        if (jumpObstacle(from, to) != JUMP_MAY_CLEAR) return false;
        return clearArc(from, to);
    }

    /** The full sweep of the jump arc against blocks, entities and dangerous cells. */
    boolean clearArc(GridPos from, GridPos to) {
        return NavigationGeometry.clearJump(feetPoint(from), feetPoint(to), bodyWidth, standingHeight,
                this::collisionFree, pos -> dangerous.contains(new GridPos(pos.getX(), pos.getY(), pos.getZ()))
                        || waterCells.contains(new GridPos(pos.getX(), pos.getY(), pos.getZ())));
    }

    static final int JUMP_NOT_NEEDED = 0, JUMP_MAY_CLEAR = 1, JUMP_BLOCKED = 2;
    /**
     * What lies between two cells of the same height: plain ground (no jump needed), a gap, an
     * obstacle or a dangerous cell (a jump may clear it), or a block at head height that no jump
     * clears. Throughout a jump arc (at most 1.25 high) the body covers 1.25 to 1.95 above the
     * take-off feet, and the body's centre crosses every cell on the line, so a collision box that
     * spans such a cell's whole footprint and that height range rules the jump out exactly.
     */
    int jumpObstacle(GridPos from, GridPos to) {
        int samples = (int) Math.ceil(horizontal(from, to) * 8);
        int lastX = from.x(), lastZ = from.z();
        boolean needed = false;
        double feet = supportHeight(from);
        for (int i = 1; i < samples; i++) {
            double t = i / (double) samples;
            int cx = (int) Math.floor(from.x() + .5 + (to.x() - from.x()) * t), cz = (int) Math.floor(from.z() + .5 + (to.z() - from.z()) * t);
            // Eight samples per block visit most cells several times in a row.
            if (cx == lastX && cz == lastZ || cx == to.x() && cz == to.z()) continue;
            lastX = cx; lastZ = cz;
            if (spansFootprint(new GridPos(cx, from.y() + 1, cz), feet + 1.25, feet + 1.95)) return JUMP_BLOCKED;
            if (needed) continue;
            GridPos cell = new GridPos(cx, from.y(), cz);
            if (!passable(cell) || requiresPlacement(cell) || dangerous(cell) || requiresSneak(cell)
                    || Math.abs(supportHeight(cell) - feet) > .5) needed = true;
        }
        return needed ? JUMP_MAY_CLEAR : JUMP_NOT_NEEDED;
    }

    /** A collision box of the cell covers its whole footprint somewhere between the two heights. */
    private boolean spansFootprint(GridPos cell, double low, double high) {
        List<AABB> parts = collisions.get(cell);
        if (parts == null) return false;
        for (AABB part : parts) {
            if (part.minX <= cell.x() + 1.0E-6 && part.maxX >= cell.x() + 1 - 1.0E-6
                    && part.minZ <= cell.z() + 1.0E-6 && part.maxZ >= cell.z() + 1 - 1.0E-6
                    && part.minY < high && part.maxY > low) return true;
        }
        return false;
    }

    private static double horizontal(GridPos from, GridPos to) {
        int dx = to.x() - from.x(), dz = to.z() - from.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private net.minecraft.world.phys.Vec3 feetPoint(GridPos pos) {
        return new net.minecraft.world.phys.Vec3(pos.x() + 0.5D,
                pos.y() + surfaceOffsets.getOrDefault(pos, 0.0D), pos.z() + 0.5D);
    }

    private boolean collisionFree(AABB box) {
        for(AABB entity : entityCollisions) if(entity.intersects(box)) return false;
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
        if (requiresPlacement(to)) return false;
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
            if (!freeBody.contains(intermediate) || requiresSneak(intermediate)
                    || dangerous.contains(intermediate)
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
