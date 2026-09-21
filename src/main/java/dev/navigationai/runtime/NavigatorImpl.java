package dev.navigationai.runtime;

import dev.navigationai.api.NavigationGoal;
import dev.navigationai.api.NavigationPolicy;
import dev.navigationai.api.NavigationResult;
import dev.navigationai.api.NavigationStatus;
import dev.navigationai.api.NavigationTask;
import dev.navigationai.api.MiningStatus;
import dev.navigationai.api.MiningTask;
import dev.navigationai.api.Navigator;
import dev.navigationai.navigation.GridPath;
import dev.navigationai.navigation.GridPathfinder;
import dev.navigationai.navigation.GridPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

final class NavigatorImpl implements Navigator {
    private final BotRuntime runtime;
    private final BotHandleImpl owner;
    private NavigationGoal goal;
    private NavigationPolicy policy = NavigationPolicy.safeDefaults();
    private NavigationTaskImpl task;
    private List<BlockPos> path = List.of();
    private int pathIndex;
    private long pathStartedNanos;
    private long requestGeneration;
    private Vec3 lastPosition;
    private Vec3 followPlanPosition;
    private int ticksWithoutProgress;
    private int repathAttempts;
    private MiningTask navigationMining;
    private long nextHazardRepathNanos;
    private WorldSnapshot plannedSnapshot;
    private boolean finalPlanningSegment;

    NavigatorImpl(BotRuntime runtime, BotHandleImpl owner) { this.runtime = runtime; this.owner = owner; }

    @Override public NavigationTask goTo(NavigationGoal goal, NavigationPolicy policy) {
        if (!(goal instanceof NavigationGoal.BlockGoal || goal instanceof NavigationGoal.PositionGoal || goal instanceof NavigationGoal.FollowGoal)) {
            throw new IllegalArgumentException("Unsupported goal type");
        }
        cancel();
        this.goal = goal;
        this.policy = policy;
        this.task = new NavigationTaskImpl();
        requestPath();
        return task;
    }

    @Override public NavigationTask follow(NavigationGoal.FollowGoal goal, NavigationPolicy policy) { return goTo(goal, policy); }
    @Override public void cancel() {
        requestGeneration++;
        if (task != null && !isTerminal(task.status())) task.cancel();
        owner.actions().stop();
        if (navigationMining != null && navigationMining.status() == MiningStatus.RUNNING) navigationMining.cancel();
        navigationMining = null;
        path = List.of(); pathIndex = 0; goal = null; followPlanPosition = null;
        repathAttempts = 0;
        plannedSnapshot = null;
        finalPlanningSegment = false;
        nextHazardRepathNanos = 0L;
    }
    @Override public NavigationStatus status() { return task == null ? NavigationStatus.IDLE : task.status(); }
    @Override public Optional<NavigationGoal> goal() { return Optional.ofNullable(goal); }

    /** Writes only an in-flight navigation request; completed and cancelled tasks are not resumed. */
    void saveState(ValueOutput output) {
        if (goal == null || task == null || isTerminal(task.status())) {
            output.putBoolean("active", false);
            return;
        }
        output.putBoolean("active", true);
        output.putInt("version", 1);
        savePolicy(output.child("policy"));
        ValueOutput goalOutput = output.child("goal");
        if (goal instanceof NavigationGoal.BlockGoal block) {
            goalOutput.putString("type", "block");
            goalOutput.putInt("x", block.position().getX());
            goalOutput.putInt("y", block.position().getY());
            goalOutput.putInt("z", block.position().getZ());
            goalOutput.putDouble("tolerance", block.tolerance());
        } else if (goal instanceof NavigationGoal.PositionGoal position) {
            goalOutput.putString("type", "position");
            goalOutput.putDouble("x", position.position().x());
            goalOutput.putDouble("y", position.position().y());
            goalOutput.putDouble("z", position.position().z());
            goalOutput.putDouble("tolerance", position.tolerance());
        } else if (goal instanceof NavigationGoal.FollowGoal follow) {
            goalOutput.putString("type", "follow");
            goalOutput.putString("targetUuid", follow.target().getUUID().toString());
            goalOutput.putString("targetName", follow.target().getGameProfile().name());
            goalOutput.putDouble("distance", follow.distance());
            goalOutput.putDouble("repathDistance", follow.repathDistance());
        }
    }

    /** Restores an in-flight request and starts a fresh plan against the current world. */
    boolean restoreState(ValueInput input) {
        if (!input.getBooleanOr("active", false)) return true;
        ValueInput goalInput = input.childOrEmpty("goal");
        String type = goalInput.getStringOr("type", "");
        NavigationGoal restored;
        try {
            restored = switch (type) {
                case "block" -> new NavigationGoal.BlockGoal(
                        new BlockPos(goalInput.getIntOr("x", 0), goalInput.getIntOr("y", 0), goalInput.getIntOr("z", 0)),
                        goalInput.getDoubleOr("tolerance", 1.0));
                case "position" -> new NavigationGoal.PositionGoal(
                        new Vec3(goalInput.getDoubleOr("x", 0.0), goalInput.getDoubleOr("y", 0.0), goalInput.getDoubleOr("z", 0.0)),
                        goalInput.getDoubleOr("tolerance", 0.75));
                case "follow" -> restoreFollowGoal(goalInput);
                default -> null;
            };
        } catch (RuntimeException invalid) {
            return false;
        }
        if (restored == null) return false;
        NavigationPolicy restoredPolicy = restorePolicy(input.childOrEmpty("policy"));
        if (restoredPolicy == null) return false;
        goTo(restored, restoredPolicy);
        return true;
    }

    private NavigationGoal restoreFollowGoal(ValueInput input) {
        String uuidText = input.getStringOr("targetUuid", "");
        if (uuidText.isBlank()) return null;
        ServerPlayer target;
        try {
            target = runtime.server().getPlayerList().getPlayer(java.util.UUID.fromString(uuidText));
        } catch (IllegalArgumentException invalidUuid) {
            return null;
        }
        if (target == null) {
            String targetName = input.getStringOr("targetName", "");
            if (!targetName.isBlank()) target = runtime.server().getPlayerList().getPlayer(targetName);
        }
        if (target == null || target.isRemoved() || target.isDeadOrDying()
                || target.level() != owner.player().level()) return null;
        double distance = input.getDoubleOr("distance", 2.0);
        double repath = input.getDoubleOr("repathDistance", 4.0);
        try {
            return new NavigationGoal.FollowGoal(target, distance, repath);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private void savePolicy(ValueOutput output) {
        output.putBoolean("allowBreaking", policy.allowBreaking());
        output.putBoolean("allowPlacing", policy.allowPlacing());
        output.putBoolean("allowWater", policy.allowWater());
        output.putBoolean("allowChunkGeneration", policy.allowChunkGeneration());
        output.putInt("maxSearchNodes", policy.maxSearchNodes());
        output.putInt("maxPathLength", policy.maxPathLength());
        output.putInt("snapshotRadius", policy.snapshotRadius());
        output.putDouble("dangerCost", policy.dangerCost());
    }

    private NavigationPolicy restorePolicy(ValueInput input) {
        try {
            return new NavigationPolicy(
                    input.getBooleanOr("allowBreaking", false),
                    input.getBooleanOr("allowPlacing", false),
                    input.getBooleanOr("allowWater", true),
                    input.getBooleanOr("allowChunkGeneration", false),
                    input.getIntOr("maxSearchNodes", 40_000),
                    input.getIntOr("maxPathLength", 2_048),
                    input.getIntOr("snapshotRadius", 32),
                    input.getDoubleOr("dangerCost", 100.0));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    void tick() {
        if (task == null || goal == null || isTerminal(task.status())) return;
        if (navigationMining != null) {
            if (navigationMining.status() == MiningStatus.RUNNING) return;
            if (navigationMining.status() == MiningStatus.FAILED || navigationMining.status() == MiningStatus.CANCELLED) {
                task.fail("navigation_break_failed", resultNodesVisited());
                return;
            }
            navigationMining = null;
            task.status = NavigationStatus.COMPUTING;
            requestPath();
            return;
        }
        if (goal instanceof NavigationGoal.FollowGoal follow) {
            if (follow.target().isRemoved() || follow.target().isDeadOrDying()
                    || follow.target().level() != owner.player().level()) {
                task.fail("follow_target_unavailable", 0);
                owner.actions().stop();
                return;
            }
            if (owner.player().distanceToSqr(follow.target()) <= follow.distance() * follow.distance()) {
                owner.actions().stop();
                path = List.of();
                pathIndex = 0;
                if (shouldRepathFollow(follow)) requestPath();
                return;
            }
            if (task.status() == NavigationStatus.RUNNING
                    && (path.isEmpty() || shouldRepathFollow(follow))) {
                requestPath();
            }
        } else if (reachedGoal()) {
            finishSuccess();
            return;
        }
        if (task.status() != NavigationStatus.RUNNING || path.isEmpty()) return;
        if (pathIndex >= path.size()) {
            if (goal instanceof NavigationGoal.FollowGoal) {
                requestPath();
            } else if (reachedGoal()) {
                finishSuccess();
            } else if (finalPlanningSegment) {
                tickFinalApproach();
            } else {
                requestPath();
            }
            return;
        }
        BlockPos next = path.get(pathIndex);
        if (hasDynamicObstacle(next) || hasNewStaticHazard(next) || hasChangedObstacle(next)) {
            requestHazardRepath();
            return;
        }
        if (!prepareTransition(next)) return;
        Vec3 target = Vec3.atCenterOf(next);
        double dx = target.x - owner.player().getX();
        double dz = target.z - owner.player().getZ();
        if (dx * dx + dz * dz < 0.14 && Math.abs(target.y - owner.player().getY()) < 1.1) {
            pathIndex++;
            if (pathIndex >= path.size()) {
                if (goal instanceof NavigationGoal.FollowGoal) requestPath();
                else if (reachedGoal()) finishSuccess();
                else if (finalPlanningSegment) tickFinalApproach();
                else requestPath();
                return;
            }
            next = path.get(pathIndex);
            target = Vec3.atCenterOf(next);
            dx = target.x - owner.player().getX(); dz = target.z - owner.player().getZ();
        }
        float yaw = (float)(Math.atan2(-dx, dz) * 180.0 / Math.PI);
        owner.actions().look(yaw, 0);
        BlockPos currentBlock = owner.player().blockPosition();
        ServerLevel level = (ServerLevel) owner.player().level();
        boolean climbing = isClimbable(level, next) || isClimbable(level, currentBlock);
        boolean jump = climbing ? next.getY() > currentBlock.getY()
                : next.getY() > currentBlock.getY() && owner.player().onGround();
        boolean sneak = climbing && next.getY() < currentBlock.getY();
        owner.actions().setMovement(0, 1, jump, !climbing, sneak);
        Vec3 current = owner.player().position();
        boolean progressed = lastPosition == null || current.distanceToSqr(lastPosition) >= 0.0004;
        if (progressed) {
            ticksWithoutProgress = 0;
            repathAttempts = 0;
        } else {
            ticksWithoutProgress++;
        }
        lastPosition = current;
        if (ticksWithoutProgress > 40) {
            if (++repathAttempts >= 3) {
                owner.actions().stop();
                task.stuck(resultNodesVisited());
            } else {
                task.status = NavigationStatus.COMPUTING;
                requestPath();
            }
        }
    }

    private void requestPath() {
        if (task == null || task.cancelled.get()) return;
        task.status = NavigationStatus.COMPUTING;
        path = List.of(); pathIndex = 0;
        owner.actions().stop();
        ServerPlayer player = owner.player();
        ServerLevel level = (ServerLevel)player.level();
        if (goal instanceof NavigationGoal.FollowGoal follow) followPlanPosition = follow.target().position();
        GridPos startPos = new GridPos(player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ());
        BlockPos requestedTarget = targetBlock();
        GridPos desired = new GridPos(requestedTarget.getX(), requestedTarget.getY(), requestedTarget.getZ());
        GridPos localTarget = clampPlanningTarget(startPos, desired);
        finalPlanningSegment = localTarget.equals(desired);
        WorldSnapshot snapshot = WorldSnapshot.capture(level, player, startPos, localTarget, policy.snapshotRadius(),
                policy.allowWater(), policy.allowChunkGeneration(), policy.allowBreaking(), policy.allowPlacing());
        plannedSnapshot = snapshot;
        GridPos goalPos = findReachableGoal(snapshot, localTarget);
        // Keep the search asynchronous even when the requested cell is blocked.
        // The pathfinder will return the authoritative `unreachable` result;
        // failing synchronously would make callers race the task lifecycle.
        if (goalPos == null) goalPos = localTarget;
        finalPlanningSegment = finalPlanningSegment && goalPos.equals(desired);
        GridPos searchGoal = goalPos;
        long started = System.nanoTime();
        pathStartedNanos = started;
        long generation = ++requestGeneration;
        try {
            runtime.pathExecutor().submit(() -> {
                GridPath result = GridPathfinder.find(snapshot, startPos, searchGoal, policy);
                runtime.server().execute(() -> applyPath(result, started, generation));
            });
        } catch (RejectedExecutionException rejected) {
            task.fail("search_queue_full", 0);
        }
    }

    private boolean shouldRepathFollow(NavigationGoal.FollowGoal follow) {
        if (follow.target().level() != owner.player().level()) return true;
        if (followPlanPosition == null) return true;
        return follow.target().position().distanceToSqr(followPlanPosition)
                > follow.repathDistance() * follow.repathDistance();
    }

    private BlockPos targetBlock() {
        if (goal instanceof NavigationGoal.BlockGoal block) return block.position();
        if (goal instanceof NavigationGoal.PositionGoal position) return BlockPos.containing(position.position());
        NavigationGoal.FollowGoal follow = (NavigationGoal.FollowGoal)goal;
        return follow.target().blockPosition();
    }

    private GridPos clampPlanningTarget(GridPos start, GridPos desired) {
        int configuredRadius = policy.allowChunkGeneration()
                ? Math.min(policy.snapshotRadius(), 16)
                : policy.snapshotRadius();
        int radius = Math.max(2, configuredRadius - 2);
        int segment = Math.max(1, Math.min(radius, policy.maxPathLength()));
        int verticalSegment = Math.max(1, Math.min(8, policy.maxPathLength()));
        int x = Math.max(start.x() - segment, Math.min(start.x() + segment, desired.x()));
        int z = Math.max(start.z() - segment, Math.min(start.z() + segment, desired.z()));
        int y = Math.max(start.y() - verticalSegment, Math.min(start.y() + verticalSegment, desired.y()));
        return new GridPos(x, y, z);
    }

    private GridPos findReachableGoal(WorldSnapshot snapshot, GridPos desired) {
        List<GridPos> candidates = List.of(
                desired,
                desired.add(0, 1, 0), desired.add(0, -1, 0),
                desired.add(1, 0, 0), desired.add(-1, 0, 0),
                desired.add(0, 0, 1), desired.add(0, 0, -1));
        for (GridPos candidate : candidates) if (snapshot.passable(candidate)) return candidate;
        return null;
    }

    private boolean prepareTransition(BlockPos next) {
        ServerPlayer player = owner.player();
        ServerLevel level = (ServerLevel) player.level();
        BlockState state = level.getBlockState(next);
        if (openDoorOrTrapdoor(level, next, state)) return true;
        if (state.is(BlockTags.CLIMBABLE)) return true;
        if (isOpenDoorOrTrapdoor(state)) return true;
        var stateShape = state.getCollisionShape(level, next);
        if (!stateShape.isEmpty() && stateShape.max(net.minecraft.core.Direction.Axis.Y) <= 0.5D
                && level.getBlockState(next.above()).getCollisionShape(level, next.above()).isEmpty()) return true;
        if (!stateShape.isEmpty()) {
            if (!policy.allowBreaking() || next.equals(player.blockPosition())) {
                task.fail("obstacle_requires_breaking", resultNodesVisited());
                return false;
            }
            navigationMining = owner.minerImpl().breakBlockWithoutCancellingNavigation(next);
            return false;
        }
        var above = level.getBlockState(next.above());
        if (!above.getCollisionShape(level, next.above()).isEmpty()) {
            if (!policy.allowBreaking() || above.is(net.minecraft.world.level.block.Blocks.BEDROCK)
                    || above.getDestroySpeed(level, next.above()) < 0.0f) {
                task.fail("obstacle_requires_breaking", resultNodesVisited());
                return false;
            }
            navigationMining = owner.minerImpl().breakBlockWithoutCancellingNavigation(next.above());
            return false;
        }
        BlockPos support = next.below();
        if (state.getCollisionShape(level, next).isEmpty()
                && level.getBlockState(support).getCollisionShape(level, support).isEmpty()
                && policy.allowPlacing()
                && !placeSupport(level, support)) {
            task.fail("no_building_material", resultNodesVisited());
            return false;
        }
        return true;
    }

    private boolean openDoorOrTrapdoor(ServerLevel level, BlockPos position, BlockState state) {
        ServerPlayer player = owner.player();
        if (state.getBlock() instanceof DoorBlock door
                && state.is(BlockTags.WOODEN_DOORS)
                && state.hasProperty(DoorBlock.OPEN) && !state.getValue(DoorBlock.OPEN)) {
            BlockPos lower = state.hasProperty(DoorBlock.HALF)
                    && state.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
                    ? position.below() : position;
            BlockState lowerState = level.getBlockState(lower);
            if (lowerState.getBlock() instanceof DoorBlock lowerDoor
                    && lowerState.is(BlockTags.WOODEN_DOORS)
                    && lowerState.hasProperty(DoorBlock.OPEN) && !lowerState.getValue(DoorBlock.OPEN)) {
                if (!player.mayInteract(level, lower)) return false;
                lowerDoor.setOpen(player, level, lowerState, lower, true);
                // Keep both halves synchronized even on server versions where
                // DoorBlock#setOpen only mutates the clicked half immediately.
                BlockState upperState = level.getBlockState(lower.above());
                if (upperState.getBlock() instanceof DoorBlock && upperState.hasProperty(DoorBlock.OPEN)) {
                    level.setBlock(lower.above(), upperState.setValue(DoorBlock.OPEN, true), 3);
                }
                return true;
            }
        }
        if (state.getBlock() instanceof TrapDoorBlock && state.is(BlockTags.WOODEN_TRAPDOORS)
                && state.hasProperty(TrapDoorBlock.OPEN) && !state.getValue(TrapDoorBlock.OPEN)) {
            if (!player.mayInteract(level, position)) return false;
            level.setBlock(position, state.setValue(TrapDoorBlock.OPEN, true), 3);
            return true;
        }
        return false;
    }

    private boolean isClimbable(ServerLevel level, BlockPos position) {
        return level.getBlockState(position).is(BlockTags.CLIMBABLE);
    }

    private boolean hasDynamicObstacle(BlockPos position) {
        ServerPlayer player = owner.player();
        ServerLevel level = (ServerLevel) player.level();
        AABB cell = new AABB(position).inflate(0.12D);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, cell)) {
            if (entity == player || !entity.isAlive() || entity.isRemoved() || entity.isSpectator()) continue;
            if (goal instanceof NavigationGoal.FollowGoal follow && entity == follow.target()) continue;
            return true;
        }
        return false;
    }

    private boolean hasNewStaticHazard(BlockPos position) {
        if (plannedSnapshot == null || policy.dangerCost() <= 0.0) return false;
        ServerLevel level = (ServerLevel) owner.player().level();
        BlockState state = level.getBlockState(position);
        boolean hazard = state.is(net.minecraft.world.level.block.Blocks.FIRE)
                || state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE)
                || state.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
                || state.is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE)
                || state.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                || state.is(net.minecraft.world.level.block.Blocks.CACTUS)
                || level.getFluidState(position).getType() == net.minecraft.world.level.material.Fluids.LAVA
                || level.getFluidState(position).getType() == net.minecraft.world.level.material.Fluids.FLOWING_LAVA
                || level.getBlockState(position.below()).is(net.minecraft.world.level.block.Blocks.FIRE)
                || level.getBlockState(position.below()).is(net.minecraft.world.level.block.Blocks.SOUL_FIRE)
                || level.getBlockState(position.below()).is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
                || level.getBlockState(position.below()).is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE)
                || level.getBlockState(position.below()).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                || level.getBlockState(position.below()).is(net.minecraft.world.level.block.Blocks.CACTUS)
                || level.getBlockState(position.below()).is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)
                || level.getFluidState(position.below()).getType() == net.minecraft.world.level.material.Fluids.LAVA
                || level.getFluidState(position.below()).getType() == net.minecraft.world.level.material.Fluids.FLOWING_LAVA;
        return hazard && !plannedSnapshot.dangerous(new GridPos(position.getX(), position.getY(), position.getZ()));
    }

    private boolean hasChangedObstacle(BlockPos position) {
        if (plannedSnapshot == null) return false;
        GridPos grid = new GridPos(position.getX(), position.getY(), position.getZ());
        if (!plannedSnapshot.passable(grid)) return false;
        ServerLevel level = (ServerLevel) owner.player().level();
        BlockState state = level.getBlockState(position);
        if (isOpenable(state) || isOpenDoorOrTrapdoor(state) || state.is(BlockTags.CLIMBABLE)) return false;
        if (isLowSurface(level, position, state)) return false;
        // A planned breakable cell is intentional when this policy allows
        // mining; let prepareTransition start the mining task instead of
        // treating it as a newly appeared dynamic obstacle.
        if (policy.allowBreaking() && state.getDestroySpeed(level, position) >= 0.0f) return false;
        if (!state.getCollisionShape(level, position).isEmpty()
                && state.getDestroySpeed(level, position) >= 0.0f) return true;
        BlockPos above = position.above();
        BlockState aboveState = level.getBlockState(above);
        return !aboveState.getCollisionShape(level, above).isEmpty()
                && !aboveState.is(BlockTags.CLIMBABLE)
                && !isOpenable(aboveState);
    }

    private boolean isOpenable(BlockState state) {
        return (state.getBlock() instanceof DoorBlock
                && state.is(BlockTags.WOODEN_DOORS)
                && state.hasProperty(DoorBlock.OPEN) && !state.getValue(DoorBlock.OPEN))
                || (state.getBlock() instanceof TrapDoorBlock && state.is(BlockTags.WOODEN_TRAPDOORS)
                && state.hasProperty(TrapDoorBlock.OPEN) && !state.getValue(TrapDoorBlock.OPEN));
    }

    private boolean isLowSurface(ServerLevel level, BlockPos position, BlockState state) {
        return !state.getCollisionShape(level, position).isEmpty()
                && state.getCollisionShape(level, position).max(net.minecraft.core.Direction.Axis.Y) <= 0.5D
                && level.getBlockState(position.above()).getCollisionShape(level, position.above()).isEmpty();
    }

    private boolean isOpenDoorOrTrapdoor(BlockState state) {
        return (state.getBlock() instanceof DoorBlock && state.hasProperty(DoorBlock.OPEN)
                && state.getValue(DoorBlock.OPEN))
                || (state.getBlock() instanceof TrapDoorBlock && state.hasProperty(TrapDoorBlock.OPEN)
                && state.getValue(TrapDoorBlock.OPEN));
    }

    private boolean requestHazardRepath() {
        long now = System.nanoTime();
        if (now < nextHazardRepathNanos) return false;
        nextHazardRepathNanos = now + 250_000_000L;
        if (++repathAttempts >= 3) {
            owner.actions().stop();
            task.stuck(resultNodesVisited());
            return true;
        }
        task.status = NavigationStatus.COMPUTING;
        requestPath();
        return true;
    }

    private void tickFinalApproach() {
        Vec3 target = goalPoint();
        Vec3 current = owner.player().position();
        double dx = target.x - current.x;
        double dz = target.z - current.z;
        float yaw = (float)(Math.atan2(-dx, dz) * 180.0 / Math.PI);
        owner.actions().look(yaw, 0);
        boolean jump = target.y > current.y + 0.35 && owner.player().onGround();
        owner.actions().setMovement(0, 1, jump, true, false);
        boolean progressed = lastPosition == null || current.distanceToSqr(lastPosition) >= 0.0004;
        if (progressed) {
            ticksWithoutProgress = 0;
            repathAttempts = 0;
        } else {
            ticksWithoutProgress++;
        }
        lastPosition = current;
        if (ticksWithoutProgress > 40) {
            if (++repathAttempts >= 3) {
                owner.actions().stop();
                task.stuck(resultNodesVisited());
            } else {
                task.status = NavigationStatus.COMPUTING;
                requestPath();
            }
        }
    }

    private Vec3 goalPoint() {
        if (goal instanceof NavigationGoal.PositionGoal position) return position.position();
        if (goal instanceof NavigationGoal.BlockGoal block) {
            return new Vec3(block.position().getX() + 0.5, block.position().getY() + 0.5,
                    block.position().getZ() + 0.5);
        }
        return owner.player().position();
    }

    private boolean placeSupport(ServerLevel level, BlockPos position) {
        ServerPlayer player = owner.player();
        if (!player.mayInteract(level, position)) return false;
        if (!hasSolidNeighbour(level, position)) return false;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!(stack.getItem() instanceof BlockItem blockItem)) continue;
            Block block = blockItem.getBlock();
            var state = block.defaultBlockState();
            if (!state.isSolid() || !state.canSurvive(level, position)) continue;
            if (level.setBlock(position, state, 3)) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }

    private boolean hasSolidNeighbour(ServerLevel level, BlockPos pos) {
        return !level.getBlockState(pos.east()).getCollisionShape(level, pos.east()).isEmpty()
                || !level.getBlockState(pos.west()).getCollisionShape(level, pos.west()).isEmpty()
                || !level.getBlockState(pos.north()).getCollisionShape(level, pos.north()).isEmpty()
                || !level.getBlockState(pos.south()).getCollisionShape(level, pos.south()).isEmpty();
    }

    private boolean reachedGoal() {
        if (goal instanceof NavigationGoal.PositionGoal position) {
            return owner.player().position().distanceTo(position.position()) <= position.tolerance();
        }
        if (goal instanceof NavigationGoal.BlockGoal block) {
            Vec3 center = new Vec3(block.position().getX() + 0.5, block.position().getY() + 0.5,
                    block.position().getZ() + 0.5);
            return owner.player().position().distanceTo(center) <= block.tolerance() + 0.5;
        }
        return false;
    }

    private boolean isTerminal(NavigationStatus status) {
        return status == NavigationStatus.CANCELLED || status == NavigationStatus.FAILED
                || status == NavigationStatus.SUCCESS || status == NavigationStatus.STUCK;
    }

    private int resultNodesVisited() { return path.size(); }

    private void applyPath(GridPath result, long started, long generation) {
        if (task == null || task.cancelled.get() || goal == null) return;
        if (generation != requestGeneration) return;
        if (result.nodes().isEmpty()) { task.fail("unreachable", result.nodesVisited()); return; }
        if (result.nodes().size() - 1 > policy.maxPathLength()) {
            task.fail("path_too_long", result.nodesVisited());
            return;
        }
        ArrayList<BlockPos> converted = new ArrayList<>(result.nodes().size());
        for (GridPos pos : result.nodes()) converted.add(new BlockPos(pos.x(), pos.y(), pos.z()));
        path = List.copyOf(converted); pathIndex = Math.min(1, path.size());
        lastPosition = owner.player().position(); ticksWithoutProgress = 0;
        task.status = NavigationStatus.RUNNING;
    }

    private void finishSuccess() {
        owner.actions().stop();
        int nodes = path.size();
        long duration = System.nanoTime() - pathStartedNanos;
        path = List.of();
        task.complete(NavigationResult.success(nodes, duration));
    }

    private final class NavigationTaskImpl implements NavigationTask {
        private volatile NavigationStatus status = NavigationStatus.IDLE;
        private final CompletableFuture<NavigationResult> completion = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        @Override public NavigationStatus status() { return status; }
        @Override public CompletableFuture<NavigationResult> completion() { return completion; }
        @Override public void cancel() {
            if (completion.isDone()) return;
            if (cancelled.compareAndSet(false, true)) {
                status = NavigationStatus.CANCELLED;
                if (NavigatorImpl.this.task == this) {
                    owner.actions().stop();
                    MiningTask mining = NavigatorImpl.this.navigationMining;
                    NavigatorImpl.this.navigationMining = null;
                    if (mining != null && mining.status() == MiningStatus.RUNNING) {
                        mining.cancel();
                    }
                    if (NavigatorImpl.this.task == this) {
                        NavigatorImpl.this.requestGeneration++;
                        NavigatorImpl.this.path = List.of();
                        NavigatorImpl.this.pathIndex = 0;
                        NavigatorImpl.this.goal = null;
                        NavigatorImpl.this.followPlanPosition = null;
                    }
                }
                completion.complete(new NavigationResult(NavigationStatus.CANCELLED, "cancelled", 0, 0));
            }
        }
        void fail(String reason, int nodes) {
            owner.actions().stop();
            path = List.of();
            pathIndex = 0;
            status = NavigationStatus.FAILED;
            completion.complete(NavigationResult.failure(reason, nodes, System.nanoTime() - pathStartedNanos));
        }
        void stuck(int nodes) {
            owner.actions().stop();
            path = List.of();
            pathIndex = 0;
            status = NavigationStatus.STUCK;
            completion.complete(NavigationResult.stuck(nodes, System.nanoTime() - pathStartedNanos));
        }
        void complete(NavigationResult result) { status = result.status(); completion.complete(result); }
    }
}
