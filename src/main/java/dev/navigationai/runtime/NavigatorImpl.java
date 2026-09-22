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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

final class NavigatorImpl implements Navigator {
    private static final double STANDING_CLEARANCE_MARGIN = 0.10D;
    private static final double CROUCHING_CLEARANCE_MARGIN = 0.02D;
    private final BotRuntime runtime;
    private final BotHandleImpl owner;
    private NavigationGoal goal;
    private NavigationGoal rootGoal;
    private List<NavigationGoal> sequence = List.of();
    private int sequenceIndex;
    private boolean sequenceLoop;
    private NavigationPolicy policy = NavigationPolicy.safeDefaults();
    private NavigationTaskImpl task;
    private List<BlockPos> path = List.of();
    private int pathIndex;
    private long pathStartedNanos;
    private long requestGeneration;
    private double lastTargetDistance = Double.POSITIVE_INFINITY;
    private Vec3 followPlanPosition;
    private int ticksWithoutProgress;
    private int repathAttempts;
    private MiningTask navigationMining;
    private long nextHazardRepathNanos;
    private WorldSnapshot plannedSnapshot;
    private boolean finalPlanningSegment;
    private boolean planningDeferred;
    private Future<?> searchFuture;
    private int budgetDeferrals;
    private final List<PlacedAction> placedActions = new ArrayList<>();

    private static final int MAX_BUDGET_DEFERRALS = 3;

    NavigatorImpl(BotRuntime runtime, BotHandleImpl owner) { this.runtime = runtime; this.owner = owner; }

    @Override public NavigationTask goTo(NavigationGoal goal, NavigationPolicy policy) {
        runtime.ensureServerThread();
        if (goal == null || policy == null) throw new NullPointerException("goal and policy");
        return startNavigation(goal, policy, 0);
    }

    /** Starts a request at a particular flattened sequence element. */
    private NavigationTask startNavigation(NavigationGoal requestedGoal, NavigationPolicy selectedPolicy,
                                           int initialSequenceIndex) {
        cancel();
        this.rootGoal = requestedGoal;
        this.sequence = flatten(requestedGoal);
        if (initialSequenceIndex < 0 || initialSequenceIndex >= sequence.size()) {
            throw new IllegalArgumentException("sequence index out of bounds");
        }
        this.sequenceIndex = initialSequenceIndex;
        this.sequenceLoop = requestedGoal instanceof NavigationGoal.PatrolGoal patrol && patrol.loop();
        this.goal = sequence.get(initialSequenceIndex);
        this.policy = selectedPolicy;
        this.task = new NavigationTaskImpl();
        this.budgetDeferrals = 0;
        requestPath();
        return task;
    }

    @Override public NavigationTask follow(NavigationGoal.FollowGoal goal, NavigationPolicy policy) { return goTo(goal, policy); }
    @Override public void cancel() {
        runtime.ensureServerThread();
        requestGeneration++;
        if (task != null && !isTerminal(task.status())) task.cancel();
        owner.actions().stop();
        if (navigationMining != null && navigationMining.status() == MiningStatus.RUNNING) navigationMining.cancel();
        navigationMining = null;
        path = List.of(); pathIndex = 0; goal = null; rootGoal = null; sequence = List.of(); sequenceIndex = 0; sequenceLoop = false; followPlanPosition = null;
        repathAttempts = 0;
        lastTargetDistance = Double.POSITIVE_INFINITY;
        plannedSnapshot = null;
        finalPlanningSegment = false;
        planningDeferred = false;
        budgetDeferrals = 0;
        cancelSearchFuture();
        nextHazardRepathNanos = 0L;
    }
    /** Stops server-side inputs and workers while retaining resumable navigation state for shutdown persistence. */
    void shutdown() {
        runtime.ensureServerThread();
        requestGeneration++;
        cancelSearchFuture();
        owner.actions().stop();
        if (navigationMining != null && navigationMining.status() == MiningStatus.RUNNING) {
            navigationMining.cancel();
        }
        navigationMining = null;
        planningDeferred = false;
    }
    @Override public NavigationStatus status() { return task == null ? NavigationStatus.IDLE : task.status(); }
    @Override public Optional<NavigationGoal> goal() { return Optional.ofNullable(goal); }

    private List<NavigationGoal> flatten(NavigationGoal requested) {
        if (requested instanceof NavigationGoal.MultiGoal multi) {
            ArrayList<NavigationGoal> result = new ArrayList<>();
            for (NavigationGoal child : multi.goals()) result.addAll(flatten(child));
            if (result.isEmpty()) throw new IllegalArgumentException("multi-goal cannot be empty");
            return List.copyOf(result);
        }
        if (requested instanceof NavigationGoal.PatrolGoal patrol) {
            ArrayList<NavigationGoal> result = new ArrayList<>();
            for (Vec3 point : patrol.waypoints()) result.add(new NavigationGoal.PositionGoal(point, patrol.tolerance()));
            return List.copyOf(result);
        }
        if (requested instanceof NavigationGoal.ReturnGoal returnGoal) {
            return List.of(new NavigationGoal.PositionGoal(returnGoal.position(), returnGoal.tolerance()));
        }
        if (requested instanceof NavigationGoal.EscortGoal escort) {
            return List.of(new NavigationGoal.FollowGoal(escort.target(), escort.distance(), escort.repathDistance()));
        }
        return List.of(requested);
    }

    /** Writes only an in-flight navigation request; completed and cancelled tasks are not resumed. */
    void saveState(ValueOutput output) {
        if (goal == null || task == null || isTerminal(task.status())) {
            output.putBoolean("active", false);
            return;
        }
        output.putBoolean("active", true);
        output.putInt("version", 2);
        output.putInt("sequenceIndex", sequenceIndex);
        savePolicy(output.child("policy"));
        saveGoal(output.child("goal"), rootGoal == null ? goal : rootGoal);
    }

    /** Restores an in-flight request and starts a fresh plan against the current world. */
    boolean restoreState(ValueInput input) {
        if (!input.getBooleanOr("active", false)) return true;
        ValueInput goalInput = input.childOrEmpty("goal");
        NavigationGoal restored;
        try {
            restored = readGoal(goalInput);
        } catch (RuntimeException invalid) {
            return false;
        }
        if (restored == null) return false;
        NavigationPolicy restoredPolicy = restorePolicy(input.childOrEmpty("policy"));
        if (restoredPolicy == null) return false;
        int savedIndex = input.getIntOr("sequenceIndex", 0);
        List<NavigationGoal> restoredSequence = flatten(restored);
        if (savedIndex < 0 || savedIndex >= restoredSequence.size()) return false;
        startNavigation(restored, restoredPolicy, savedIndex);
        return true;
    }

    private void saveGoal(ValueOutput output, NavigationGoal value) {
        if (value instanceof NavigationGoal.BlockGoal block) {
            output.putString("type", "block");
            output.putInt("x", block.position().getX()); output.putInt("y", block.position().getY()); output.putInt("z", block.position().getZ());
            output.putDouble("tolerance", block.tolerance());
        } else if (value instanceof NavigationGoal.PositionGoal position) {
            savePosition(output, "position", position.position(), position.tolerance());
        } else if (value instanceof NavigationGoal.LookAtGoal lookAt) {
            savePosition(output, "look_at", lookAt.position(), lookAt.tolerance());
        } else if (value instanceof NavigationGoal.ReturnGoal returnGoal) {
            savePosition(output, "return", returnGoal.position(), returnGoal.tolerance());
        } else if (value instanceof NavigationGoal.DimensionGoal dimension) {
            savePosition(output, "dimension", dimension.position(), dimension.tolerance());
            output.putString("dimension", dimension.dimension().identifier().toString());
        } else if (value instanceof NavigationGoal.FollowGoal follow) {
            saveFollow(output, "follow", follow.target(), follow.distance(), follow.repathDistance());
        } else if (value instanceof NavigationGoal.EscortGoal escort) {
            saveFollow(output, "escort", escort.target(), escort.distance(), escort.repathDistance());
        } else if (value instanceof NavigationGoal.PatrolGoal patrol) {
            output.putString("type", "patrol"); output.putBoolean("loop", patrol.loop()); output.putDouble("tolerance", patrol.tolerance());
            ValueOutput.ValueOutputList points = output.childrenList("points");
            for (Vec3 point : patrol.waypoints()) { ValueOutput child = points.addChild(); savePosition(child, "point", point, 0.0); }
        } else if (value instanceof NavigationGoal.MultiGoal multi) {
            output.putString("type", "multi");
            ValueOutput.ValueOutputList goals = output.childrenList("goals");
            for (NavigationGoal child : multi.goals()) saveGoal(goals.addChild(), child);
        } else {
            throw new IllegalArgumentException("Unsupported goal type: " + value);
        }
    }

    private static void savePosition(ValueOutput output, String type, Vec3 position, double tolerance) {
        output.putString("type", type); output.putDouble("x", position.x()); output.putDouble("y", position.y());
        output.putDouble("z", position.z()); output.putDouble("tolerance", tolerance);
    }

    private static void saveFollow(ValueOutput output, String type, ServerPlayer target, double distance, double repath) {
        output.putString("type", type); output.putString("targetUuid", target.getUUID().toString());
        output.putString("targetName", target.getGameProfile().name()); output.putDouble("distance", distance);
        output.putDouble("repathDistance", repath);
    }

    private NavigationGoal readGoal(ValueInput input) {
        String type = input.getStringOr("type", "");
        return switch (type) {
            case "block" -> new NavigationGoal.BlockGoal(new BlockPos(input.getIntOr("x", 0), input.getIntOr("y", 0), input.getIntOr("z", 0)), input.getDoubleOr("tolerance", 1.0));
            case "position" -> new NavigationGoal.PositionGoal(readPosition(input), input.getDoubleOr("tolerance", 0.75));
            case "look_at" -> new NavigationGoal.LookAtGoal(readPosition(input), input.getDoubleOr("tolerance", 0.75));
            case "return" -> new NavigationGoal.ReturnGoal(readPosition(input), input.getDoubleOr("tolerance", 0.75));
            case "dimension" -> new NavigationGoal.DimensionGoal(
                    ResourceKey.create(Registries.DIMENSION, Identifier.parse(input.getStringOr("dimension", Level.OVERWORLD.identifier().toString()))),
                    readPosition(input), input.getDoubleOr("tolerance", 0.75));
            case "follow" -> restoreFollowGoal(input);
            case "escort" -> {
                NavigationGoal.FollowGoal follow = restoreFollowGoal(input);
                yield follow == null ? null : new NavigationGoal.EscortGoal(follow.target(), follow.distance(), follow.repathDistance());
            }
            case "patrol" -> {
                List<Vec3> points = input.childrenListOrEmpty("points").stream().map(NavigatorImpl::readPosition).toList();
                yield new NavigationGoal.PatrolGoal(points, input.getBooleanOr("loop", false), input.getDoubleOr("tolerance", 0.75));
            }
            case "multi" -> {
                List<NavigationGoal> goals = input.childrenListOrEmpty("goals").stream().map(this::readGoal).toList();
                yield new NavigationGoal.MultiGoal(goals);
            }
            default -> null;
        };
    }

    private static Vec3 readPosition(ValueInput input) {
        return new Vec3(input.getDoubleOr("x", 0.0), input.getDoubleOr("y", 0.0), input.getDoubleOr("z", 0.0));
    }

    private NavigationGoal.FollowGoal restoreFollowGoal(ValueInput input) {
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
        output.putInt("maxFallDistance", policy.maxFallDistance());
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
                    input.getDoubleOr("dangerCost", 100.0),
                    input.getIntOr("maxFallDistance", 3));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    void tick() {
        if (task == null || goal == null || isTerminal(task.status())) return;
        if (planningDeferred) {
            planningDeferred = false;
            requestPath();
            if (planningDeferred || task.status() == NavigationStatus.COMPUTING) return;
        }
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
                // A local segment can end at a reachable neighbour when the
                // requested cell is blocked. If the bot did not move, do not
                // keep rebuilding the same one-cell route forever.
                if (++repathAttempts >= 3) {
                    owner.actions().stop();
                    task.stuck(resultNodesVisited());
                } else {
                    requestPath();
                }
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
            lastTargetDistance = Double.POSITIVE_INFINITY;
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
        BlockPos edgeStart = pathIndex > 0 ? path.get(pathIndex - 1) : currentBlock;
        boolean jumpGap = isJumpTransition(edgeStart, next);
        boolean swimming = policy.allowWater()
                && (isWater(level, next) || isWater(level, currentBlock) || isWater(level, next.below()));
        // Fake players do not receive the client packet that normally toggles
        // swimming. Keep the pose explicit while a planned transition enters,
        // crosses, or exits water so vanilla travel applies water movement.
        owner.player().setSwimming(swimming);
        boolean jump = jumpGap ? owner.player().onGround()
                : climbing ? next.getY() > currentBlock.getY()
                : next.getY() > currentBlock.getY() && owner.player().onGround();
        if (swimming && next.getY() > currentBlock.getY()) jump = true;
        boolean sneak = requiresSneak(currentBlock) || requiresSneak(next)
                || (climbing && next.getY() < currentBlock.getY());
        owner.actions().setMovement(0, 1, jump, !climbing && !sneak, sneak);
        Vec3 current = owner.player().position();
        boolean progressed = progressedToward(target);
        if (progressed) {
            ticksWithoutProgress = 0;
            repathAttempts = 0;
        } else {
            ticksWithoutProgress++;
        }
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
        if (task == null || task.cancelled.get() || isTerminal(task.status())) return;
        boolean replan = !planningDeferred && (plannedSnapshot != null || !path.isEmpty());
        if (replan) runtime.recordReplan();
        long generation = ++requestGeneration;
        cancelSearchFuture();
        task.status = NavigationStatus.COMPUTING;
        path = List.of(); pathIndex = 0;
        owner.actions().stop();
        if (runtime.shouldDeferPathSearch(searchPriority())) {
            runtime.recordBackpressureDeferral();
            planningDeferred = true;
            return;
        }
        if (!runtime.tryAcquireSnapshotBudget()) {
            planningDeferred = true;
            return;
        }
        planningDeferred = false;
        ServerPlayer player = owner.player();
        // Looking at a point is an immediate orientation goal.  It must not
        // be treated as a request to walk into the target block (which may be
        // above the bot or otherwise non-walkable).
        if (goal instanceof NavigationGoal.LookAtGoal lookAt) {
            pathStartedNanos = System.nanoTime();
            lookAt(lookAt.position());
            finishSuccess();
            return;
        }
        if (goal instanceof NavigationGoal.DimensionGoal dimension
                && !player.level().dimension().equals(dimension.dimension())) {
            ServerLevel targetLevel = runtime.server().getLevel(dimension.dimension());
            if (targetLevel == null) {
                task.fail("dimension_unavailable", 0);
                return;
            }
            runtime.releaseNavigationTickets(owner.id());
            boolean moved = player.teleportTo(targetLevel, dimension.position().x(), dimension.position().y(),
                    dimension.position().z(), Set.of(), player.getYRot(), player.getXRot(), false);
            if (!moved) {
                task.fail("dimension_change_rejected", 0);
                return;
            }
        }
        player = owner.player();
        ServerLevel level = (ServerLevel)player.level();
        if (goal instanceof NavigationGoal.FollowGoal follow) followPlanPosition = follow.target().position();
        GridPos startPos = new GridPos(player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ());
        BlockPos requestedTarget = targetBlock();
        GridPos desired = new GridPos(requestedTarget.getX(), requestedTarget.getY(), requestedTarget.getZ());
        // Search towards the real goal.  WorldSnapshot bounds the amount of
        // world read around the bot, while GridPathfinder can return a useful
        // frontier path when the goal is beyond that snapshot.
        finalPlanningSegment = false;
        long snapshotStarted = System.nanoTime();
        WorldSnapshot snapshot;
        try {
            if (policy.allowChunkGeneration()) {
                runtime.updateNavigationTickets(owner.id(), level, startPos, policy.snapshotRadius());
            } else {
                runtime.releaseNavigationTickets(owner.id());
            }
            snapshot = WorldSnapshot.capture(level, player, startPos, desired, policy.snapshotRadius(),
                    policy.allowWater(), policy.allowChunkGeneration(), policy.allowBreaking(), policy.allowPlacing(),
                    runtime::tryAcquireChunkGeneration);
        } finally {
            runtime.recordSnapshot(System.nanoTime() - snapshotStarted);
        }
        plannedSnapshot = snapshot;
        GridPos searchGoal = selectSearchGoal(snapshot, desired);
        long started = System.nanoTime();
        pathStartedNanos = started;
        try {
            searchFuture = runtime.pathScheduler().submit(searchPriority(), () -> {
                long searchStarted = System.nanoTime();
                GridPath result = GridPathfinder.find(snapshot, startPos, searchGoal, policy,
                        runtime.pathNodeBudget()::tryConsume, true);
                runtime.recordPathSearch(result.nodesVisited(), System.nanoTime() - searchStarted);
                runtime.server().execute(() -> applyPath(result, started, generation));
            });
            runtime.recordPathSearchSubmitted();
        } catch (RejectedExecutionException rejected) {
            searchFuture = null;
            runtime.recordPathSearchRejected();
            task.fail("search_queue_full", 0);
        }
    }

    private int searchPriority() {
        return goal instanceof NavigationGoal.FollowGoal ? 10 : 20;
    }

    /**
     * A BlockGoal denotes the block to approach, which is normally solid and
     * therefore not itself a walkable cell.  Keep the real block as the
     * primary goal, but when it is inside the current snapshot select the
     * nearest walkable neighbour for the final segment.  If it is outside the
     * snapshot, leave the real goal untouched so the frontier planner can
     * advance toward it and make this decision later.
     */
    private GridPos selectSearchGoal(WorldSnapshot snapshot, GridPos desired) {
        if (!(goal instanceof NavigationGoal.BlockGoal) || snapshot.passable(desired)) return desired;
        List<GridPos> candidates = List.of(
                desired.add(0, 1, 0), desired.add(0, -1, 0),
                desired.add(1, 0, 0), desired.add(-1, 0, 0),
                desired.add(0, 0, 1), desired.add(0, 0, -1));
        for (GridPos candidate : candidates) if (snapshot.passable(candidate)) return candidate;
        return desired;
    }

    private void cancelSearchFuture() {
        if (searchFuture != null) {
            if (runtime.pathScheduler().cancel(searchFuture)) runtime.recordPathSearchCancelled();
            searchFuture = null;
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
        if (goal instanceof NavigationGoal.LookAtGoal lookAt) return BlockPos.containing(lookAt.position());
        if (goal instanceof NavigationGoal.ReturnGoal returnGoal) return BlockPos.containing(returnGoal.position());
        if (goal instanceof NavigationGoal.DimensionGoal dimension) return BlockPos.containing(dimension.position());
        NavigationGoal.FollowGoal follow = (NavigationGoal.FollowGoal)goal;
        return follow.target().blockPosition();
    }

    private boolean prepareTransition(BlockPos next) {
        ServerPlayer player = owner.player();
        ServerLevel level = (ServerLevel) player.level();
        BlockPos current = player.blockPosition();
        if (isLava(level, next) || (!policy.allowWater() && isWater(level, next))) {
            requestHazardRepath();
            return false;
        }
        if (current.getY() - next.getY() >= 2) {
            // During a planned drop the player is briefly airborne and may
            // already have crossed into the landing column. Validate against
            // the path's ledge node while falling instead of treating that
            // transient air cell as a changed obstacle.
            BlockPos plannedFrom = pathIndex > 0 ? path.get(pathIndex - 1) : current;
            GridPos fromGrid = new GridPos(plannedFrom.getX(), plannedFrom.getY(), plannedFrom.getZ());
            GridPos toGrid = new GridPos(next.getX(), next.getY(), next.getZ());
            if (plannedSnapshot == null || !plannedSnapshot.canDrop(fromGrid, toGrid)) {
                requestHazardRepath();
                return false;
            }
        }
        BlockState state = level.getBlockState(next);
        // A closed trapdoor can be part of a deliberate crawl-height tunnel.
        // Keep it closed and use the crouching pose instead of opening the
        // block and losing the clearance information from the plan.
        if (requiresSneak(next) && isLowSurface(level, next, state)) return true;
        if (openDoorOrTrapdoor(level, next, state)) return true;
        if (state.is(BlockTags.CLIMBABLE)) return true;
        if (isOpenDoorOrTrapdoor(state)) return true;
        var stateShape = state.getCollisionShape(level, next);
        if (!stateShape.isEmpty() && stateShape.max(net.minecraft.core.Direction.Axis.Y) <= 0.5D
                && (hasClearanceAbove(level, next, Pose.STANDING)
                || hasClearanceAbove(level, next, Pose.CROUCHING))) return true;
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
            if (hasBodyClearance(level, next, Pose.STANDING)
                    || hasBodyClearance(level, next, Pose.CROUCHING)) return true;
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
                && level.getBlockState(support).getCollisionShape(level, support).isEmpty()) {
            // Water is a valid support-less transition only when the policy
            // explicitly allows swimming. The cell immediately above water is
            // also a valid swim-up transition; it must not be mistaken for a
            // missing bridge support.
            if (policy.allowWater() && (isWater(level, next) || isWater(level, support))) return true;
            // Empty air must never be treated as a normal walking cell after
            // the world changed under the bot.
            if (!policy.allowPlacing() || !placeSupport(level, support)) {
                task.fail("no_building_material", resultNodesVisited());
                return false;
            }
        }
        return true;
    }

    private boolean openDoorOrTrapdoor(ServerLevel level, BlockPos position, BlockState state) {
        ServerPlayer player = owner.player();
        if (state.getBlock() instanceof DoorBlock door
                && door.type().canOpenByHand()
                && state.hasProperty(DoorBlock.OPEN) && !state.getValue(DoorBlock.OPEN)) {
            BlockPos lower = state.hasProperty(DoorBlock.HALF)
                    && state.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
                    ? position.below() : position;
            BlockState lowerState = level.getBlockState(lower);
            if (lowerState.getBlock() instanceof DoorBlock lowerDoor
                    && lowerDoor.type().canOpenByHand()
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
        if (state.getBlock() instanceof FenceGateBlock gate
                && state.hasProperty(FenceGateBlock.OPEN) && !state.getValue(FenceGateBlock.OPEN)) {
            if (!player.mayInteract(level, position)) return false;
            level.setBlock(position, state.setValue(FenceGateBlock.OPEN, true), 3);
            return true;
        }
        return false;
    }

    private boolean isClimbable(ServerLevel level, BlockPos position) {
        return level.getBlockState(position).is(BlockTags.CLIMBABLE);
    }

    private boolean isJumpTransition(BlockPos from, BlockPos to) {
        if (from.getY() != to.getY()) return false;
        int dx = Math.abs(to.getX() - from.getX());
        int dz = Math.abs(to.getZ() - from.getZ());
        return (dx == 0 && dz >= 2 && dz <= 4) || (dz == 0 && dx >= 2 && dx <= 4);
    }

    private boolean requiresSneak(BlockPos position) {
        if (plannedSnapshot == null) return false;
        return plannedSnapshot.requiresSneak(new GridPos(position.getX(), position.getY(), position.getZ()));
    }

    private boolean hasBodyClearance(ServerLevel level, BlockPos position, Pose pose) {
        BlockState state = level.getBlockState(position);
        if (!state.getCollisionShape(level, position).isEmpty()) return false;
        return hasClearanceAbove(level, position, pose);
    }

    private boolean hasClearanceAbove(ServerLevel level, BlockPos position, Pose pose) {
        BlockState above = level.getBlockState(position.above());
        var aboveShape = above.getCollisionShape(level, position.above());
        double height = owner.player().getDimensions(pose).height();
        double margin = pose == Pose.CROUCHING ? CROUCHING_CLEARANCE_MARGIN : STANDING_CLEARANCE_MARGIN;
        return aboveShape.isEmpty()
                || 1.0D + aboveShape.min(net.minecraft.core.Direction.Axis.Y)
                >= height + margin;
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
        ServerLevel level = (ServerLevel) owner.player().level();
        if (isLava(level, position) || (!policy.allowWater() && isWater(level, position))) return true;
        GridPos grid = new GridPos(position.getX(), position.getY(), position.getZ());
        if (!plannedSnapshot.passable(grid)) return false;
        BlockState state = level.getBlockState(position);
        if (isOpenable(state) || isOpenDoorOrTrapdoor(state) || state.is(BlockTags.CLIMBABLE)) return false;
        if (isLowSurface(level, position, state)) return false;
        if (hasBodyClearance(level, position, Pose.STANDING)
                || hasBodyClearance(level, position, Pose.CROUCHING)) return false;
        BlockPos support = position.below();
        if (state.getCollisionShape(level, position).isEmpty()
                && level.getBlockState(position.above()).getCollisionShape(level, position.above()).isEmpty()
                && level.getBlockState(support).getCollisionShape(level, support).isEmpty()
                && !isWater(level, position)
                && !policy.allowPlacing()) return true;
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
        return (state.getBlock() instanceof DoorBlock door
                && door.type().canOpenByHand()
                && state.hasProperty(DoorBlock.OPEN) && !state.getValue(DoorBlock.OPEN))
                || (state.getBlock() instanceof TrapDoorBlock && state.is(BlockTags.WOODEN_TRAPDOORS)
                && state.hasProperty(TrapDoorBlock.OPEN) && !state.getValue(TrapDoorBlock.OPEN))
                || (state.getBlock() instanceof FenceGateBlock
                && state.hasProperty(FenceGateBlock.OPEN) && !state.getValue(FenceGateBlock.OPEN));
    }

    private boolean isLowSurface(ServerLevel level, BlockPos position, BlockState state) {
        return !state.getCollisionShape(level, position).isEmpty()
                && state.getCollisionShape(level, position).max(net.minecraft.core.Direction.Axis.Y) <= 0.5D
                && (hasClearanceAbove(level, position, Pose.STANDING)
                || hasClearanceAbove(level, position, Pose.CROUCHING));
    }

    private boolean isOpenDoorOrTrapdoor(BlockState state) {
        return (state.getBlock() instanceof DoorBlock && state.hasProperty(DoorBlock.OPEN)
                && state.getValue(DoorBlock.OPEN))
                || (state.getBlock() instanceof TrapDoorBlock && state.hasProperty(TrapDoorBlock.OPEN)
                && state.getValue(TrapDoorBlock.OPEN))
                || (state.getBlock() instanceof FenceGateBlock && state.hasProperty(FenceGateBlock.OPEN)
                && state.getValue(FenceGateBlock.OPEN));
    }

    private boolean isWater(ServerLevel level, BlockPos position) {
        var fluid = level.getFluidState(position).getType();
        return fluid == net.minecraft.world.level.material.Fluids.WATER
                || fluid == net.minecraft.world.level.material.Fluids.FLOWING_WATER;
    }

    private boolean isLava(ServerLevel level, BlockPos position) {
        var fluid = level.getFluidState(position).getType();
        return fluid == net.minecraft.world.level.material.Fluids.LAVA
                || fluid == net.minecraft.world.level.material.Fluids.FLOWING_LAVA;
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
        boolean progressed = progressedToward(target);
        if (progressed) {
            ticksWithoutProgress = 0;
            repathAttempts = 0;
        } else {
            ticksWithoutProgress++;
        }
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
        if (goal instanceof NavigationGoal.LookAtGoal lookAt) return lookAt.position();
        if (goal instanceof NavigationGoal.ReturnGoal returnGoal) return returnGoal.position();
        if (goal instanceof NavigationGoal.DimensionGoal dimension) return dimension.position();
        if (goal instanceof NavigationGoal.BlockGoal block) {
            return new Vec3(block.position().getX() + 0.5, block.position().getY() + 0.5,
                    block.position().getZ() + 0.5);
        }
        return owner.player().position();
    }

    /** Counts progress only when the active waypoint gets measurably closer. */
    private boolean progressedToward(Vec3 target) {
        double distance = owner.player().position().distanceToSqr(target);
        if (!Double.isFinite(lastTargetDistance)) {
            // Establish the baseline without counting the first observation
            // after a plan/replan as movement. Otherwise a motionless bot
            // resets the stuck timer on every fresh path forever.
            lastTargetDistance = distance;
            return false;
        }
        boolean progressed = lastTargetDistance - distance >= 0.0004D;
        lastTargetDistance = distance;
        return progressed;
    }

    private boolean placeSupport(ServerLevel level, BlockPos position) {
        ServerPlayer player = owner.player();
        if (!player.mayInteract(level, position)) return false;
        if (!hasSolidNeighbour(level, position)) return false;
        BlockState existing = level.getBlockState(position);
        if (!existing.canBeReplaced() || !level.getFluidState(position).isEmpty()) return false;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!(stack.getItem() instanceof BlockItem blockItem)) continue;
            Block block = blockItem.getBlock();
            var state = block.defaultBlockState();
            if (!state.isSolid() || block instanceof net.minecraft.world.level.block.FallingBlock
                    || !state.canSurvive(level, position)) continue;
            if (level.setBlock(position, state, 3)
                    && level.getBlockState(position).getBlock() == block) {
                ItemStack returnedStack = stack.copy();
                stack.shrink(1);
                returnedStack.setCount(1);
                placedActions.add(new PlacedAction(level.dimension(), position.immutable(), existing, state,
                        slot, returnedStack));
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
        if (goal instanceof NavigationGoal.LookAtGoal lookAt) {
            return owner.player().position().distanceTo(lookAt.position()) <= lookAt.tolerance();
        }
        if (goal instanceof NavigationGoal.ReturnGoal returnGoal) {
            return owner.player().position().distanceTo(returnGoal.position()) <= returnGoal.tolerance();
        }
        if (goal instanceof NavigationGoal.DimensionGoal dimension) {
            return owner.player().level().dimension().equals(dimension.dimension())
                    && owner.player().position().distanceTo(dimension.position()) <= dimension.tolerance();
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
        if (task == null || task.cancelled.get() || goal == null || isTerminal(task.status())) return;
        if (generation != requestGeneration) return;
        // A global node budget may end a search after it has already produced
        // a usable frontier.  Execute that frontier first; only an empty
        // result should be deferred and retried.
        if (result.budgetExhausted() && result.nodes().isEmpty()) {
            runtime.recordNodeBudgetDeferral();
            if (++budgetDeferrals > MAX_BUDGET_DEFERRALS) {
                task.fail("search_budget_exhausted", result.nodesVisited());
                return;
            }
            planningDeferred = true;
            task.status = NavigationStatus.COMPUTING;
            return;
        }
        budgetDeferrals = 0;
        if (result.nodes().isEmpty()) { task.fail("unreachable", result.nodesVisited()); return; }
        if (result.nodes().size() - 1 > policy.maxPathLength()) {
            task.fail("path_too_long", result.nodesVisited());
            return;
        }
        ArrayList<BlockPos> converted = new ArrayList<>(result.nodes().size());
        for (GridPos pos : result.nodes()) converted.add(new BlockPos(pos.x(), pos.y(), pos.z()));
        path = List.copyOf(converted); pathIndex = Math.min(1, path.size());
        finalPlanningSegment = result.goalReached();
        lastTargetDistance = Double.POSITIVE_INFINITY; ticksWithoutProgress = 0;
        task.status = NavigationStatus.RUNNING;
    }

    private void finishSuccess() {
        if (goal instanceof NavigationGoal.LookAtGoal) lookAt(goalPoint());
        owner.actions().stop();
        if (advanceSequence()) return;
        runtime.releaseNavigationTickets(owner.id());
        requestGeneration++;
        cancelSearchFuture();
        planningDeferred = false;
        int nodes = path.size();
        long duration = System.nanoTime() - pathStartedNanos;
        path = List.of();
        placedActions.clear();
        task.complete(NavigationResult.success(nodes, duration));
    }

    private void lookAt(Vec3 target) {
        Vec3 from = owner.player().getEyePosition();
        double dx = target.x - from.x;
        double dy = target.y - from.y;
        double dz = target.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        owner.actions().look((float)(Math.atan2(-dx, dz) * 180.0 / Math.PI),
                (float)(Math.atan2(-dy, horizontal) * 180.0 / Math.PI));
    }

    private boolean advanceSequence() {
        if (sequenceIndex + 1 < sequence.size()) {
            sequenceIndex++;
        } else if (sequenceLoop && !sequence.isEmpty()) {
            sequenceIndex = 0;
        } else {
            return false;
        }
        goal = sequence.get(sequenceIndex);
        path = List.of();
        pathIndex = 0;
        plannedSnapshot = null;
        finalPlanningSegment = false;
        lastTargetDistance = Double.POSITIVE_INFINITY;
        ticksWithoutProgress = 0;
        repathAttempts = 0;
        followPlanPosition = null;
        task.status = NavigationStatus.COMPUTING;
        requestPath();
        return true;
    }

    /** Compensates simple bridge placements when a navigation transaction fails. */
    private void rollbackPlacedActions() {
        if (placedActions.isEmpty()) return;
        ServerPlayer player = owner.player();
        for (int index = placedActions.size() - 1; index >= 0; index--) {
            PlacedAction action = placedActions.get(index);
            ServerLevel level = runtime.server().getLevel(action.dimension());
            if (level == null) continue;
            if (level.getBlockState(action.position()).equals(action.placedState())) {
                level.setBlock(action.position(), action.previousState(), 3);
                ItemStack returned = action.returnedStack().copy();
                ItemStack current = player.getInventory().getItem(action.slot());
                if (current.isEmpty()) {
                    player.getInventory().setItem(action.slot(), returned);
                } else if (current.getItem() == returned.getItem()
                        && current.getCount() < current.getMaxStackSize()) {
                    current.grow(1);
                } else if (!player.getInventory().add(returned.copy())) {
                    player.drop(returned.copy(), false);
                }
            }
        }
        placedActions.clear();
    }

    private record PlacedAction(ResourceKey<Level> dimension, BlockPos position,
                                BlockState previousState, BlockState placedState,
                                int slot, ItemStack returnedStack) { }

    private final class NavigationTaskImpl implements NavigationTask {
        private volatile NavigationStatus status = NavigationStatus.IDLE;
        private final CompletableFuture<NavigationResult> completion = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        @Override public NavigationStatus status() { return status; }
        @Override public CompletableFuture<NavigationResult> completion() { return completion; }
        @Override public void cancel() {
            NavigatorImpl.this.runtime.ensureServerThread();
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
                        NavigatorImpl.this.planningDeferred = false;
                        NavigatorImpl.this.cancelSearchFuture();
                        NavigatorImpl.this.path = List.of();
                        NavigatorImpl.this.pathIndex = 0;
                        NavigatorImpl.this.goal = null;
                        NavigatorImpl.this.followPlanPosition = null;
                        NavigatorImpl.this.rollbackPlacedActions();
                        NavigatorImpl.this.runtime.releaseNavigationTickets(owner.id());
                    }
                }
                completion.complete(new NavigationResult(NavigationStatus.CANCELLED, "cancelled", 0, 0));
            }
        }
        void fail(String reason, int nodes) {
            owner.actions().stop();
            NavigatorImpl.this.requestGeneration++;
            NavigatorImpl.this.cancelSearchFuture();
            NavigatorImpl.this.planningDeferred = false;
            path = List.of();
            pathIndex = 0;
            status = NavigationStatus.FAILED;
            NavigatorImpl.this.rollbackPlacedActions();
            NavigatorImpl.this.runtime.releaseNavigationTickets(owner.id());
            completion.complete(NavigationResult.failure(reason, nodes, System.nanoTime() - pathStartedNanos));
        }
        void stuck(int nodes) {
            owner.actions().stop();
            NavigatorImpl.this.requestGeneration++;
            NavigatorImpl.this.cancelSearchFuture();
            NavigatorImpl.this.planningDeferred = false;
            path = List.of();
            pathIndex = 0;
            status = NavigationStatus.STUCK;
            NavigatorImpl.this.rollbackPlacedActions();
            NavigatorImpl.this.runtime.releaseNavigationTickets(owner.id());
            completion.complete(NavigationResult.stuck(nodes, System.nanoTime() - pathStartedNanos));
        }
        void complete(NavigationResult result) { status = result.status(); completion.complete(result); }
    }
}
