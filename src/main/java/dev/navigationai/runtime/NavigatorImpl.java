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
import dev.navigationai.navigation.GridTransition;
import dev.navigationai.navigation.TransitionRules;
import dev.navigationai.api.NavigationDiagnostics;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
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
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(NavigatorImpl.class);
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
    private List<GridTransition> transitions = List.of();
    private final MovementController movement = new MovementController();
    private WorldSnapshot liveTransitionView;
    private GridTransition validatedTransition;
    private boolean recoverAfterLanding;
    private int pathIndex;
    private long requestGeneration;
    private double lastTargetDistance = Double.POSITIVE_INFINITY;
    private Vec3 followPlanPosition;
    private int ticksWithoutProgress;
    private int repathAttempts;
    private Vec3 stallAnchor;
    private int ticksNearStallAnchor;
    private MiningTask navigationMining;
    private long nextHazardRepathNanos;
    private WorldSnapshot plannedSnapshot;
    private WorldSnapshot.Capture pendingCapture;
    private GridPos captureStart, captureGoal;
    private long captureGeneration, captureNanos;
    private int captureRadius;
    private int expandedSnapshotRadius;
    private boolean finalPlanningSegment;
    private boolean planningDeferred;
    private boolean waitingForFrontierChunks;
    private int frontierChunkWaitAttempts;
    private int nextFrontierRetryTick;
    private Future<?> searchFuture;
    private int budgetDeferrals;
    private final List<PlacedAction> placedActions = new ArrayList<>();
    private boolean waitingForLanding;
    private boolean backgroundPlanning;
    private int lastFollowPathTick;
    private int planningRequestedTick;

    private static final int MAX_BUDGET_DEFERRALS = 3;
    private static final int MAX_FRONTIER_CHUNK_RETRIES = 20;
    private static final int FRONTIER_CHUNK_RETRY_DELAY_TICKS = 5;
    private static final int SNAPSHOT_CELLS_PER_BOT_TICK = 2_048;
    private static final int MAX_TICKS_IN_ONE_AREA = 300;

    NavigatorImpl(BotRuntime runtime, BotHandleImpl owner) { this.runtime = runtime; this.owner = owner; }

    @Override public NavigationTask goTo(NavigationGoal goal, NavigationPolicy policy) {
        runtime.ensureServerThread();
        owner.ensureActive();
        if (goal == null || policy == null) throw new NullPointerException("goal and policy");
        return startNavigation(goal, policy, 0);
    }

    /** Starts a request at a particular flattened sequence element. */
    private NavigationTask startNavigation(NavigationGoal requestedGoal, NavigationPolicy selectedPolicy,
                                           int initialSequenceIndex) {
        return startNavigation(requestedGoal, selectedPolicy, initialSequenceIndex, List.of());
    }

    private NavigationTask startNavigation(NavigationGoal requestedGoal, NavigationPolicy selectedPolicy,
                                           int initialSequenceIndex, List<PlacedAction> restoredActions) {
        NavigationTaskImpl previousTask = task;
        cancel();
        // Completing the old cancellation can synchronously start a newer
        // request. That newer request wins; report this call as cancelled
        // without replacing its unfinished task or returning the wrong goal.
        if (task != previousTask && task != null && !isTerminal(task.status())) {
            NavigationTaskImpl superseded = new NavigationTaskImpl();
            superseded.cancel();
            return superseded;
        }
        owner.ensureActive();
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
        placedActions.addAll(restoredActions);
        this.budgetDeferrals = 0;
        requestPath();
        return task;
    }

    @Override public NavigationTask follow(NavigationGoal.FollowGoal goal, NavigationPolicy policy) { return goTo(goal, policy); }
    @Override public void cancel() {
        runtime.ensureServerThread();
        requestGeneration++;
        NavigationTaskImpl cancelledTask = task;
        if (cancelledTask != null && !isTerminal(cancelledTask.status())) cancelledTask.cancel();
        // Completing a task invokes callbacks synchronously. A callback may have
        // started another navigation request while the old task was cancelled.
        if (task != cancelledTask) return;
        owner.actions().stop();
        MiningTask mining = navigationMining;
        navigationMining = null;
        if (mining != null && mining.status() == MiningStatus.RUNNING) mining.cancel();
        if (task != cancelledTask) return;
        path = List.of(); pathIndex = 0; goal = null; rootGoal = null; sequence = List.of(); sequenceIndex = 0; sequenceLoop = false; followPlanPosition = null;
        repathAttempts = 0;
        lastTargetDistance = Double.POSITIVE_INFINITY;
        stallAnchor = null;
        ticksNearStallAnchor = 0;
        plannedSnapshot = null;
        pendingCapture = null;
        captureRadius = 0;
        expandedSnapshotRadius = 0;
        finalPlanningSegment = false;
        planningDeferred = false;
        waitingForFrontierChunks = false;
        frontierChunkWaitAttempts = 0;
        nextFrontierRetryTick = 0;
        budgetDeferrals = 0;
        cancelSearchFuture();
        nextHazardRepathNanos = 0L;
        waitingForLanding = false;
        backgroundPlanning = false;
        recoverAfterLanding = false;
        transitions = List.of(); movement.reset(); liveTransitionView = null; validatedTransition = null;
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
        pendingCapture = null;
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
        output.putInt("version", 3);
        output.putInt("sequenceIndex", sequenceIndex);
        savePolicy(output.child("policy"));
        saveGoal(output.child("goal"), rootGoal == null ? goal : rootGoal);
        ValueOutput.ValueOutputList placements = output.childrenList("placements");
        for (PlacedAction action : placedActions) {
            ValueOutput placement = placements.addChild();
            placement.putString("dimension", action.dimension().identifier().toString());
            placement.store("position", BlockPos.CODEC, action.position());
            placement.store("previousState", BlockState.CODEC, action.previousState());
            placement.store("placedState", BlockState.CODEC, action.placedState());
            placement.putInt("slot", action.slot());
            placement.store("returnedStack", ItemStack.CODEC, action.returnedStack());
        }
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
        List<PlacedAction> restoredActions;
        try {
            restoredActions = input.childrenListOrEmpty("placements").stream().map(placement -> {
                ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                        Identifier.parse(placement.getString("dimension").orElseThrow()));
                BlockPos position = placement.read("position", BlockPos.CODEC).orElseThrow();
                BlockState previous = placement.read("previousState", BlockState.CODEC).orElseThrow();
                BlockState placed = placement.read("placedState", BlockState.CODEC).orElseThrow();
                int slot = placement.getInt("slot").orElseThrow();
                ItemStack returned = placement.read("returnedStack", ItemStack.CODEC).orElseThrow();
                if (slot < 0 || slot >= owner.player().getInventory().getContainerSize()
                        || returned.isEmpty() || returned.getCount() != 1) {
                    throw new IllegalArgumentException("Invalid persisted placement");
                }
                return new PlacedAction(dimension, position.immutable(), previous, placed, slot, returned);
            }).toList();
        } catch (RuntimeException invalid) {
            return false;
        }
        startNavigation(restored, restoredPolicy, savedIndex, restoredActions);
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
        task.observe();
        if (recoverAfterLanding && stableForArrival(owner.player())) {
            recoverAfterLanding = false;
            requestPath();
            return;
        }
        if (waitingForLanding) {
            if (!stableForArrival(owner.player())) return;
            waitingForLanding = false;
            if (reachedGoal()) finishSuccess();
            else requestPath();
            return;
        }
        // Snapshot capture can legitimately take many ticks for an expanded
        // route. Count only ticks when the bot is actually following a path.
        if (navigationMining != null) {
            stallAnchor = null;
            ticksNearStallAnchor = 0;
        } else if (task.status() == NavigationStatus.RUNNING
                && !(goal instanceof NavigationGoal.FollowGoal) && checkSpatialStall()) return;
        if (task.status() == NavigationStatus.COMPUTING) holdPositionWhilePlanning();
        if (pendingCapture != null) {
            advanceCapture();
            if (task.status() == NavigationStatus.COMPUTING) return;
        }
        if (waitingForFrontierChunks) {
            if (runtime.server().getTickCount() < nextFrontierRetryTick) return;
            waitingForFrontierChunks = false;
            requestPath();
            return;
        }
        if (planningDeferred) {
            planningDeferred = false;
            requestPath(backgroundPlanning);
            if (task.status() == NavigationStatus.COMPUTING) return;
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
            if (owner.player().distanceToSqr(follow.target()) <= follow.distance() * follow.distance()
                    && (stableForArrival(owner.player()) || activeTransition() == null)) {
                owner.actions().stop();
                path = List.of();
                pathIndex = 0;
                return;
            }
            if (task.status() == NavigationStatus.RUNNING
                    && !isPlanning() && (path.isEmpty()
                    || (runtime.server().getTickCount() - lastFollowPathTick >= 20 && shouldRepathFollow(follow)))) {
                requestPath(!path.isEmpty() && pathIndex < path.size());
            }
        } else if (reachedGoal() && (stableForArrival(owner.player()) || activeTransition() == null)) {
            finishSuccess();
            return;
        }
        if (task.status() != NavigationStatus.RUNNING || path.isEmpty()) return;
        if (!isPlanning()) {
            for (int i = pathIndex + 1; i < Math.min(path.size(), pathIndex + 7); i++) {
                if (hasNewStaticHazard(path.get(i)) || hasChangedObstacle(path.get(i))) {
                    requestPath(true);
                    break;
                }
            }
        }
        // Plan the next local segment early, while the remaining validated route is still executable.
        if (!finalPlanningSegment && !isPlanning() && pathIndex < path.size()
                && path.size() - pathIndex <= 8 && stableForArrival(owner.player())) requestPath(true);
        if (pathIndex >= path.size()) {
            if (goal instanceof NavigationGoal.FollowGoal) {
                requestNextFollowPath();
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
                    finishStuck("segment_end", null);
                } else {
                    requestNextFollowPath();
                }
            }
            return;
        }
        BlockPos next = path.get(pathIndex);
        if (hasDynamicObstacle(next) || hasNewStaticHazard(next) || hasChangedObstacle(next)) {
            requestHazardRepath();
            return;
        }
        GridTransition transition = activeTransition();
        if (transition == null || !validateTransition(transition)) {
            requestHazardRepath();
            return;
        }
        if (!prepareTransition(next)) return;
        if (!readyAfterActions(transition)) { requestHazardRepath(); return; }
        Vec3 target = transitionTarget(transition);
        double dx = target.x - owner.player().getX();
        double dz = target.z - owner.player().getZ();
        if (dx * dx + dz * dz < transition.landing().horizontalTolerance() * transition.landing().horizontalTolerance()
                && Math.abs(target.y - owner.player().getY()) < transition.landing().verticalTolerance()
                && (!transition.landing().stable() || stableForArrival(owner.player()))) {
            pathIndex++;
            lastTargetDistance = Double.POSITIVE_INFINITY;
            if (pathIndex >= path.size()) {
                if (goal instanceof NavigationGoal.FollowGoal) requestNextFollowPath();
                else if (reachedGoal()) finishSuccess();
                else if (finalPlanningSegment) tickFinalApproach();
                else requestNextFollowPath();
                return;
            }
            next = path.get(pathIndex);
            if (hasDynamicObstacle(next) || hasNewStaticHazard(next) || hasChangedObstacle(next)) {
                requestHazardRepath();
                return;
            }
            transition = activeTransition();
            if (transition == null || !validateTransition(transition)) { requestHazardRepath(); return; }
            if (!prepareTransition(next)) return;
            if (!readyAfterActions(transition)) { requestHazardRepath(); return; }
            target = transitionTarget(transition);
            dx = target.x - owner.player().getX(); dz = target.z - owner.player().getZ();
        }
        float yaw = (float)(Math.atan2(-dx, dz) * 180.0 / Math.PI);
        owner.actions().look(yaw, 0);
        ServerPlayer player = owner.player();
        Vec3 velocity = player.getDeltaMovement();
        boolean swim = transition.type() == GridTransition.Type.SWIM;
        player.setSwimming(swim);
        boolean straight = continuesStraight(transition);
        MovementController.Input input = movement.update(transition,
                new MovementController.State(player.getX(), player.getY(), player.getZ(), velocity.x, velocity.y,
                        velocity.z, player.onGround(), player.getFoodData().getFoodLevel() > 6), straight);
        owner.actions().setMovement(input.strafe(), input.forward(), input.jump(), input.sprint(), input.sneak());
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
                finishStuck("no_progress", next);
            } else {
                task.status = NavigationStatus.COMPUTING;
                requestPath();
            }
        }
    }

    private boolean isPlanning() {
        return pendingCapture != null || searchFuture != null || planningDeferred;
    }

    private GridTransition activeTransition() {
        return pathIndex > 0 && pathIndex - 1 < transitions.size() ? transitions.get(pathIndex - 1) : null;
    }

    private Vec3 transitionTarget(GridTransition transition) {
        return new Vec3(transition.to().x() + .5, transition.toSupportHeight(), transition.to().z() + .5);
    }

    private boolean continuesStraight(GridTransition transition) {
        if (pathIndex >= transitions.size()) return false;
        GridTransition following = transitions.get(pathIndex);
        if (following.type() != GridTransition.Type.STEP || !following.actions().isEmpty()) return false;
        double ax = transition.to().x() - transition.from().x(), az = transition.to().z() - transition.from().z();
        double bx = following.to().x() - following.from().x(), bz = following.to().z() - following.from().z();
        return ax * bx + az * bz > 0 && Math.abs(ax * bz - az * bx) < .01;
    }

    private boolean validateTransition(GridTransition transition) {
        ServerLevel level = (ServerLevel) owner.player().level();
        if (!transition.equals(validatedTransition) || liveTransitionView == null
                || liveTransitionView.sprintAvailable() != (owner.player().getFoodData().getFoodLevel() > 6)
                || !liveTransitionView.geometryCurrent(level)) {
            liveTransitionView = WorldSnapshot.transitionView(level, owner.player(), transition.from(), transition.to(),
                    policy.allowWater(), policy.allowBreaking(), policy.allowPlacing());
            validatedTransition = transition;
        }
        return TransitionRules.valid(liveTransitionView, transition, policy);
    }

    private boolean readyAfterActions(GridTransition transition) {
        return !transition.gapJump() || hasLiveJumpClearance((ServerLevel) owner.player().level(),
                blockPos(transition.from()), blockPos(transition.to()));
    }

    private static BlockPos blockPos(GridPos pos) { return new BlockPos(pos.x(), pos.y(), pos.z()); }

    private void requestNextFollowPath() {
        if (isPlanning()) {
            task.status = NavigationStatus.COMPUTING;
            backgroundPlanning = false;
            owner.actions().stop();
            brakeHorizontal();
        } else requestPath();
    }

    private void brakeHorizontal() {
        if (owner.player().onGround() || owner.player().isInWater()) {
            Vec3 motion = owner.player().getDeltaMovement();
            owner.player().setDeltaMovement(0.0D, motion.y, 0.0D);
        }
    }

    private void requestPath() { requestPath(false); }

    private void requestPath(boolean keepFollowingPath) {
        if (task == null || task.cancelled.get() || isTerminal(task.status())) return;
        if (!planningDeferred) planningRequestedTick = runtime.server().getTickCount();
        boolean replan = !planningDeferred && (plannedSnapshot != null || !path.isEmpty());
        if (replan) { runtime.recordReplan(); task.replans++; }
        long generation = ++requestGeneration;
        cancelSearchFuture();
        pendingCapture = null;
        backgroundPlanning = keepFollowingPath && task.status() == NavigationStatus.RUNNING
                && pathIndex < path.size();
        if (!backgroundPlanning) {
            task.status = NavigationStatus.COMPUTING;
            path = List.of(); pathIndex = 0;
            transitions = List.of(); movement.reset(); validatedTransition = null;
            owner.actions().stop();
            // Stop residual momentum at an exhausted or unsafe segment boundary.
            brakeHorizontal();
            holdPositionWhilePlanning();
        }
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
        if (!(goal instanceof NavigationGoal.FollowGoal) && reachedGoal()) {
            finishSuccess();
            return;
        }
        if (goal instanceof NavigationGoal.FollowGoal follow) followPlanPosition = follow.target().position();
        GridPos startPos = new GridPos(player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ());
        if (backgroundPlanning && !finalPlanningSegment && !path.isEmpty()) {
            // Extend the captured area from the frontier instead of searching the same old boundary again.
            BlockPos frontier = path.getLast();
            startPos = new GridPos(frontier.getX(), frontier.getY(), frontier.getZ());
        }
        BlockPos requestedTarget = targetBlock();
        GridPos desired = new GridPos(requestedTarget.getX(), requestedTarget.getY(), requestedTarget.getZ());
        // Search towards the real goal.  WorldSnapshot bounds the amount of
        // world read around the bot, while GridPathfinder can return a useful
        // frontier path when the goal is beyond that snapshot.
        if (!backgroundPlanning) finalPlanningSegment = false;
        // Start with a small local capture. If a visible goal is unreachable
        // inside it, retry with more of the policy's search area so an access
        // route beginning behind the bot can be found.
        int distance = Math.max(Math.abs(desired.x() - startPos.x()), Math.abs(desired.z() - startPos.z()));
        // Long requests are handled by successive local frontier plans. Keep
        // each server-thread capture small enough that a bot does not stand
        // idle for several seconds before every segment.
        int radius = Math.min(policy.snapshotRadius(),
                Math.max(expandedSnapshotRadius, Math.max(8, Math.min(16, distance + 4))));
        captureRadius = radius;
        if (policy.allowChunkGeneration()) {
            runtime.updateNavigationTickets(owner.id(), level, startPos, radius);
        } else {
            runtime.releaseNavigationTickets(owner.id());
        }
        captureStart = startPos;
        captureGoal = desired;
        captureGeneration = generation;
        captureNanos = 0;
        WorldSnapshot reused = plannedSnapshot == null ? null : plannedSnapshot.reuseIfCurrent(level, player,
                startPos, desired, policy.allowWater(), policy.allowBreaking(), policy.allowPlacing());
        if (reused != null && expandedSnapshotRadius == 0 && (!backgroundPlanning || finalPlanningSegment)
                && (reused.passable(desired) || distance > radius)) {
            task.reusedSnapshotCells += reused.reusedCells();
            runtime.recordSnapshot(0);
            submitSearch(reused);
            return;
        }
        pendingCapture = WorldSnapshot.beginCapture(level, player, startPos, desired, radius,
                policy.allowWater(), policy.allowChunkGeneration(), policy.allowBreaking(), policy.allowPlacing(),
                runtime::tryAcquireChunkGeneration, plannedSnapshot);
        advanceCapture();
    }

    private void holdPositionWhilePlanning() {
        if (!policy.allowWater()) return;
        ServerPlayer player = owner.player();
        ServerLevel level = (ServerLevel) player.level();
        BlockPos feet = player.blockPosition();
        boolean inWater = player.isInWater() || isWater(level, feet) || isWater(level, feet.below());
        owner.actions().setMovement(0, 0, inWater, false, false);
        player.setSwimming(inWater);
        if (inWater) {
            Vec3 motion = player.getDeltaMovement();
            player.setDeltaMovement(0.0D, motion.y, 0.0D);
        }
    }

    private void advanceCapture() {
        if (pendingCapture == null) return;
        int cells = runtime.acquireSnapshotCells(SNAPSHOT_CELLS_PER_BOT_TICK);
        if (cells == 0) return;
        long captureStepStarted = System.nanoTime();
        boolean complete;
        try {
            complete = pendingCapture.step(cells, runtime.planningDeadline());
        } finally {
            captureNanos += System.nanoTime() - captureStepStarted;
        }
        if (!complete) return;
        WorldSnapshot snapshot = pendingCapture.result();
        pendingCapture = null;
        runtime.recordSnapshot(captureNanos);
        task.reusedSnapshotCells += snapshot.reusedCells();
        submitSearch(snapshot);
    }

    private void submitSearch(WorldSnapshot snapshot) {
        if (task == null || task.cancelled.get() || isTerminal(task.status())
                || captureGeneration != requestGeneration) return;
        ServerPlayer player = owner.player();
        GridPos current = new GridPos(player.blockPosition().getX(), player.blockPosition().getY(),
                player.blockPosition().getZ());
        if (backgroundPlanning && pathIndex < path.size()) {
            // A future node is a stable search anchor even while the player is crossing air.
            int anchorIndex = -1;
            for (int i = pathIndex; i <= Math.min(pathIndex + 3, path.size() - 1); i++) {
                if (!safeRetainedNode(i)) break;
                anchorIndex = i;
            }
            if (anchorIndex >= 0) {
                BlockPos anchor = path.get(anchorIndex);
                GridPos anchorGrid = new GridPos(anchor.getX(), anchor.getY(), anchor.getZ());
                if (snapshot.passable(anchorGrid)) current = anchorGrid;
            }
        }
        if (!current.equals(captureStart) && !snapshot.passable(current)) {
            requestPath(backgroundPlanning);
            return;
        }
        // Water currents and buoyancy can move a fake player while a snapshot
        // is being collected. The captured area is still valid at the new
        // position; restarting the whole capture would keep it in COMPUTING.
        GridPos startPos = current;
        GridPos desired = captureGoal;
        long generation = captureGeneration;
        Set<GridPos> searchGoals = selectSearchGoals(snapshot, desired);
        NavigationPolicy searchPolicy = policy;
        try {
            searchFuture = runtime.pathScheduler().submit(searchPriority(), () -> {
                long searchStarted = System.nanoTime();
                GridPath result = GridPathfinder.findToAny(snapshot, startPos, searchGoals, desired, searchPolicy,
                        runtime.pathNodeBudget()::tryConsume, true);
                runtime.recordPathSearch(result.nodesVisited(), System.nanoTime() - searchStarted);
                runtime.server().execute(() -> applyPath(result, snapshot, generation));
            });
            runtime.recordPathSearchSubmitted();
        } catch (RejectedExecutionException rejected) {
            searchFuture = null;
            runtime.recordPathSearchRejected();
            if (!backgroundPlanning) task.fail("search_queue_full", resultNodesVisited());
            backgroundPlanning = false;
        }
    }

    private int searchPriority() {
        int base = goal instanceof NavigationGoal.FollowGoal ? 10 : 20;
        return base + Math.min(20, Math.max(0, runtime.server().getTickCount() - planningRequestedTick) / 20);
    }

    private boolean safeRetainedNode(int index) {
        BlockPos node = path.get(index);
        if (hasNewStaticHazard(node) || hasChangedObstacle(node) || hasDynamicObstacle(node)) return false;
        if (index > 0 && index - 1 < transitions.size()) {
            GridTransition edge = transitions.get(index - 1);
            ServerLevel level = (ServerLevel) owner.player().level();
            if (Math.abs(node.getY() + NavigationGeometry.feetOffset(level, node) - edge.toSupportHeight()) > .05) return false;
            if (edge.gapJump() && !hasLiveJumpClearance(level, blockPos(edge.from()), node)) return false;
        }
        return true;
    }

    /**
     * A BlockGoal denotes the block to approach, which is normally solid and
     * therefore not itself a walkable cell.  Keep the real block as the
     * primary goal, but when it is inside the current snapshot select the
     * nearest walkable neighbour for the final segment.  If it is outside the
     * snapshot, leave the real goal untouched so the frontier planner can
     * advance toward it and make this decision later.
     */
    private Set<GridPos> selectSearchGoals(WorldSnapshot snapshot, GridPos desired) {
        if (goal instanceof NavigationGoal.PositionGoal position) {
            // Search the arrival region, including safe neighbours of an occupied destination.
            // Otherwise A* can pick a long jump directly onto the caller and the live executor refuses it forever.
            Set<GridPos> endpoints = new HashSet<>();
            int radius = (int) Math.min(4, Math.ceil(position.tolerance()));
            for (int dx = -radius; dx <= radius; dx++) for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    GridPos candidate = desired.add(dx, dy, dz);
                    Vec3 point = new Vec3(candidate.x() + .5, snapshot.supportHeight(candidate), candidate.z() + .5);
                    if (snapshot.passable(candidate) && point.distanceTo(position.position()) <= position.tolerance()) {
                        endpoints.add(candidate);
                    }
                }
            }
            if (!endpoints.isEmpty()) return Set.copyOf(endpoints);
        }
        if (!(goal instanceof NavigationGoal.BlockGoal) || snapshot.passable(desired)) return Set.of(desired);
        List<GridPos> candidates = List.of(
                desired.add(0, 1, 0), desired.add(0, -1, 0),
                desired.add(1, 0, 0), desired.add(-1, 0, 0),
                desired.add(0, 0, 1), desired.add(0, 0, -1));
        Set<GridPos> endpoints = new HashSet<>();
        for (GridPos candidate : candidates) if (snapshot.passable(candidate)) endpoints.add(candidate);
        return endpoints.isEmpty() ? Set.of(desired) : Set.copyOf(endpoints);
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
                && (hasBodyClearance(level, next, Pose.STANDING)
                || hasBodyClearance(level, next, Pose.CROUCHING))) return true;
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
        if (PassageBlocks.handOpenableTrapdoor(state)
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

    private boolean hasJumpHeadroom(ServerLevel level, BlockPos position) {
        BlockPos head = position.above();
        BlockState state = level.getBlockState(head);
        if ((state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock)
                && state.hasProperty(DoorBlock.OPEN)) {
            // Open doors and trapdoors keep a side-panel collision shape. The
            // centred jump column can still be clear at this block.
            if (!state.getValue(DoorBlock.OPEN)) {
                if (!openDoorOrTrapdoor(level, head, state)) return false;
                state = level.getBlockState(head);
            }
            if (!state.getValue(DoorBlock.OPEN)) return false;
            double halfWidth = owner.player().getDimensions(Pose.STANDING).width() / 2.0D;
            AABB jumpColumn = new AABB(0.5D - halfWidth, 0.0D, 0.5D - halfWidth,
                    0.5D + halfWidth, 1.0D, 0.5D + halfWidth);
            if (state.getCollisionShape(level, head).toAabbs().stream().anyMatch(jumpColumn::intersects)) {
                return false;
            }
            return hasClearanceAbove(level, head, Pose.STANDING);
        }
        return state.getCollisionShape(level, head).isEmpty()
                && hasBodyClearance(level, head, Pose.STANDING);
    }

    private boolean hasLiveJumpClearance(ServerLevel level, BlockPos from, BlockPos to) {
        var dimensions = owner.player().getDimensions(Pose.STANDING);
        Vec3 start = new Vec3(from.getX() + 0.5D, from.getY() + NavigationGeometry.feetOffset(level, from),
                from.getZ() + 0.5D);
        Vec3 end = new Vec3(to.getX() + 0.5D, to.getY() + NavigationGeometry.feetOffset(level, to),
                to.getZ() + 0.5D);
        return NavigationGeometry.clearJump(start, end, dimensions.width(), dimensions.height(), box -> {
            if (!level.noBlockCollision(owner.player(), box)) return false;
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
                if (entity != owner.player() && entity.isAlive() && !entity.isSpectator()
                        && !(goal instanceof NavigationGoal.FollowGoal follow && entity == follow.target())) return false;
            }
            return true;
        }, pos -> NavigationSafety.dangerous(level, pos) || isWater(level, pos));
    }

    private boolean requiresSneak(BlockPos position) {
        ServerLevel level = (ServerLevel) owner.player().level();
        return !hasBodyClearance(level, position, Pose.STANDING)
                && hasBodyClearance(level, position, Pose.CROUCHING);
    }

    private boolean hasBodyClearance(ServerLevel level, BlockPos position, Pose pose) {
        var dimensions = owner.player().getDimensions(pose);
        double margin = pose == Pose.CROUCHING ? CROUCHING_CLEARANCE_MARGIN : STANDING_CLEARANCE_MARGIN;
        return NavigationGeometry.bodyFits(level, position, NavigationGeometry.feetOffset(level, position),
                dimensions.width(), dimensions.height() + margin, false);
    }

    private boolean hasClearanceAbove(ServerLevel level, BlockPos position, Pose pose) {
        BlockState above = level.getBlockState(position.above());
        var aboveShape = above.getCollisionShape(level, position.above());
        double height = owner.player().getDimensions(pose).height();
        double margin = pose == Pose.CROUCHING ? CROUCHING_CLEARANCE_MARGIN : STANDING_CLEARANCE_MARGIN;
        double offset = NavigationGeometry.feetOffset(level, position);
        return aboveShape.isEmpty()
                || hasCenteredOpenPassage(above, aboveShape, height + offset + margin - 1.0D)
                || 1.0D + aboveShape.min(net.minecraft.core.Direction.Axis.Y)
                >= height + offset + margin;
    }

    private boolean hasCenteredOpenPassage(BlockState state,
                                           net.minecraft.world.phys.shapes.VoxelShape shape, double height) {
        if (!(state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock)
                || !state.hasProperty(DoorBlock.OPEN) || !state.getValue(DoorBlock.OPEN)) return false;
        double halfWidth = owner.player().getDimensions(Pose.STANDING).width() / 2.0D;
        AABB body = new AABB(0.5D - halfWidth, 0.0D, 0.5D - halfWidth,
                0.5D + halfWidth, height, 0.5D + halfWidth);
        for (AABB part : shape.toAabbs()) if (part.intersects(body)) return false;
        return true;
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
        return NavigationSafety.dangerous(level, position) && !plannedSnapshot.dangerous(new GridPos(position.getX(), position.getY(), position.getZ()));
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
                || (PassageBlocks.handOpenableTrapdoor(state)
                && state.hasProperty(TrapDoorBlock.OPEN) && !state.getValue(TrapDoorBlock.OPEN))
                || (state.getBlock() instanceof FenceGateBlock
                && state.hasProperty(FenceGateBlock.OPEN) && !state.getValue(FenceGateBlock.OPEN));
    }

    private boolean isLowSurface(ServerLevel level, BlockPos position, BlockState state) {
        return !state.getCollisionShape(level, position).isEmpty()
                && state.getCollisionShape(level, position).max(net.minecraft.core.Direction.Axis.Y) <= 0.5D
                && (hasBodyClearance(level, position, Pose.STANDING)
                || hasBodyClearance(level, position, Pose.CROUCHING));
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
        if (!stableForArrival(owner.player())) {
            // Replanning from a transient air cell can erase the landing contract.
            // Keep steering at the landing and repair the route once it is stable.
            recoverAfterLanding = true;
            GridTransition transition = activeTransition();
            if (transition != null) {
                Vec3 target = transitionTarget(transition);
                Vec3 current = owner.player().position();
                owner.actions().look((float) (Math.atan2(current.x - target.x, target.z - current.z) * 180 / Math.PI), 0);
                owner.actions().setMovement(0, .2f, false, false, false);
            }
            return false;
        }
        owner.actions().stop();
        brakeHorizontal();
        long now = System.nanoTime();
        if (now < nextHazardRepathNanos) return false;
        nextHazardRepathNanos = now + 250_000_000L;
        if (++repathAttempts >= 3) {
            owner.actions().stop();
            finishStuck("changed_obstacle", pathIndex < path.size() ? path.get(pathIndex) : null);
            return true;
        }
        task.status = NavigationStatus.COMPUTING;
        requestPath();
        return true;
    }

    private void finishStuck(String reason, BlockPos next) {
        ServerPlayer player = owner.player();
        LOGGER.warn("Navigation stuck: bot={} reason={} pos={} next={} goal={} pathIndex={} pathSize={} pose={} velocity={}",
                owner.name(), reason, player.position(), next, goal, pathIndex, path.size(),
                player.getPose(), player.getDeltaMovement());
        task.stuck(resultNodesVisited());
    }

    private void tickFinalApproach() {
        if (!stableForArrival(owner.player())) {
            // The last drop node can be passed horizontally before landing.
            // Its airborne cell needs no bridge; let vanilla finish the fall.
            owner.actions().stop();
            waitingForLanding = true;
            return;
        }
        Vec3 target = goalPoint();
        Vec3 current = owner.player().position();
        double dx = target.x - current.x;
        double dz = target.z - current.z;
        ServerPlayer player = owner.player();
        ServerLevel level = (ServerLevel) player.level();
        double horizontal = Math.hypot(dx, dz);
        double step = Math.min(0.2D, horizontal);
        Vec3 ahead = horizontal < 1.0E-6D ? current
                : current.add(dx / horizontal * step, 0, dz / horizontal * step);
        BlockPos cell = BlockPos.containing(ahead);
        boolean sneak = requiresSneak(player.blockPosition()) || requiresSneak(cell);
        Pose pose = sneak ? Pose.CROUCHING : Pose.STANDING;
        var dimensions = player.getDimensions(pose);
        AABB aheadBody = NavigationGeometry.body(ahead.x, ahead.y, ahead.z,
                dimensions.width(), dimensions.height());
        for (int x = (int) Math.floor(aheadBody.minX); x <= (int) Math.floor(aheadBody.maxX - 1.0E-6D); x++) {
            for (int z = (int) Math.floor(aheadBody.minZ); z <= (int) Math.floor(aheadBody.maxZ - 1.0E-6D); z++) {
                BlockPos touched = new BlockPos(x, (int) Math.floor(ahead.y), z);
                if (isLava(level, touched) || (!policy.allowWater() && isWater(level, touched))
                        || (policy.dangerCost() > 0.0D && NavigationSafety.dangerous(level, touched))) {
                    requestHazardRepath();
                    return;
                }
            }
        }
        if (hasDynamicObstacle(cell) || hasNewStaticHazard(cell) || hasChangedObstacle(cell)
                || !level.noBlockCollision(player, aheadBody)
                || isLava(level, cell) || (!policy.allowWater() && isWater(level, cell))) {
            requestHazardRepath();
            return;
        }
        if (!prepareTransition(cell)) return;
        float yaw = (float)(Math.atan2(-dx, dz) * 180.0 / Math.PI);
        owner.actions().look(yaw, 0);
        boolean jump = target.y > current.y + 0.6D && player.onGround()
                && !sneak && hasJumpHeadroom(level, player.blockPosition());
        boolean swimming = policy.allowWater() && player.isInWater();
        player.setSwimming(swimming);
        if (horizontal < 0.6D && player.onGround()) brakeHorizontal();
        float forward = (float) Math.min(1.0D, Math.max(0.15D, horizontal * 1.5D));
        owner.actions().setMovement(0, forward, jump || swimming && target.y > current.y, false, sneak);
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
                finishStuck("final_approach", targetBlock());
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
            Vec3 center = Vec3.atCenterOf(block.position());
            Vec3 current = owner.player().position();
            double horizontal = Math.hypot(center.x - current.x, center.z - current.z);
            double vertical = center.y - current.y;
            if (horizontal > 1.0E-6D && Math.abs(vertical) <= block.tolerance()) {
                // Approach the boundary of the arrival sphere, which can be
                // outside a solid target. Looking ahead towards its centre
                // otherwise rejects a safe final step into the tolerance.
                double radius = Math.sqrt(Math.max(0, block.tolerance() * block.tolerance() - vertical * vertical));
                return center.add((current.x - center.x) / horizontal * radius, 0,
                        (current.z - center.z) / horizontal * radius);
            }
            return center;
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

    private boolean checkSpatialStall() {
        Vec3 position = owner.player().position();
        if (stallAnchor == null || Math.hypot(position.x - stallAnchor.x, position.z - stallAnchor.z) >= 1.5D
                || Math.abs(position.y - stallAnchor.y) >= 2.0D) {
            stallAnchor = position;
            ticksNearStallAnchor = 0;
            return false;
        }
        if (++ticksNearStallAnchor <= MAX_TICKS_IN_ONE_AREA) return false;
        owner.actions().stop();
        finishStuck("oscillating_in_place", pathIndex < path.size() ? path.get(pathIndex) : null);
        return true;
    }

    private boolean placeSupport(ServerLevel level, BlockPos position) {
        ServerPlayer player = owner.player();
        if (!player.mayInteract(level, position)) return false;
        if (!hasSolidNeighbour(level, position)) return false;
        BlockState existing = level.getBlockState(position);
        if (!existing.canBeReplaced() || !level.getFluidState(position).isEmpty()) return false;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!NavigationSafety.buildingMaterial(stack, level, position)) continue;
            BlockItem blockItem = (BlockItem) stack.getItem();
            Block block = blockItem.getBlock();
            var state = block.defaultBlockState();
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
            return owner.player().position().distanceTo(Vec3.atCenterOf(block.position())) <= block.tolerance();
        }
        return false;
    }

    private boolean isTerminal(NavigationStatus status) {
        return status == NavigationStatus.CANCELLED || status == NavigationStatus.FAILED
                || status == NavigationStatus.SUCCESS || status == NavigationStatus.STUCK;
    }

    private int resultNodesVisited() { return task == null ? 0 : task.nodesVisited; }

    private void applyPath(GridPath result, WorldSnapshot snapshot, long generation) {
        if (task == null || task.cancelled.get() || goal == null || isTerminal(task.status())) return;
        if (generation != requestGeneration) return;
        searchFuture = null;
        task.nodesVisited = (int) Math.min(Integer.MAX_VALUE, (long) task.nodesVisited + result.nodesVisited());
        boolean keepOldPath = backgroundPlanning && task.status() == NavigationStatus.RUNNING
                && pathIndex < path.size();
        backgroundPlanning = false;
        lastFollowPathTick = runtime.server().getTickCount();
        if (keepOldPath && result.nodes().isEmpty()) return;
        // A global node budget may end a search after it has already produced
        // a usable frontier.  Execute that frontier first; only an empty
        // result should be deferred and retried.
        if (result.budgetExhausted() && result.nodes().isEmpty()) {
            runtime.recordNodeBudgetDeferral();
            if (++budgetDeferrals > MAX_BUDGET_DEFERRALS) {
                task.fail("search_budget_exhausted", resultNodesVisited());
                return;
            }
            planningDeferred = true;
            task.status = NavigationStatus.COMPUTING;
            return;
        }
        budgetDeferrals = 0;
        if (result.nodes().isEmpty()) {
            // The fake player may reach the edge of its loaded area before
            // the server finishes loading the next chunk. Retrying on later
            // ticks lets normal player-driven chunk loading catch up instead
            // of turning a 40–60 block route into an immediate failure.
            if (snapshot.hasUnloadedChunks()) {
                deferForFrontierChunks(resultNodesVisited());
                return;
            }
            if (result.nodesVisited() > 0 && captureRadius < policy.snapshotRadius()) {
                expandedSnapshotRadius = Math.min(policy.snapshotRadius(),
                        Math.max(captureRadius + 8, captureRadius * 2));
                requestPath();
                return;
            }
            task.fail("unreachable", resultNodesVisited());
            return;
        }
        waitingForFrontierChunks = false;
        frontierChunkWaitAttempts = 0;
        if (result.nodes().size() - 1 > policy.maxPathLength()) {
            task.fail("path_too_long", resultNodesVisited());
            return;
        }
        ArrayList<BlockPos> converted = new ArrayList<>(result.nodes().size());
        for (GridPos pos : result.nodes()) converted.add(new BlockPos(pos.x(), pos.y(), pos.z()));
        int joinIndex = converted.indexOf(owner.player().blockPosition());
        if (keepOldPath && (joinIndex < 0 || !stableForArrival(owner.player()))) {
            // Preserve the active edge (including a jump in flight) and splice at a common future node.
            int oldJoin = -1, newJoin = -1;
            for (int i = pathIndex; i < path.size(); i++) {
                int candidate = converted.indexOf(path.get(i));
                if (candidate >= 0) { oldJoin = i; newJoin = candidate; break; }
            }
            if (oldJoin < 0) return;
            for (int i = pathIndex; i <= oldJoin; i++) if (!safeRetainedNode(i)) return;
            ArrayList<BlockPos> joined = new ArrayList<>(path.subList(0, oldJoin + 1));
            joined.addAll(converted.subList(newJoin + 1, converted.size()));
            ArrayList<GridTransition> joinedTransitions = new ArrayList<>(transitions.subList(0, oldJoin));
            joinedTransitions.addAll(result.transitions().subList(newJoin, result.transitions().size()));
            path = List.copyOf(joined); transitions = List.copyOf(joinedTransitions);
            plannedSnapshot = snapshot; finalPlanningSegment = result.goalReached();
            return;
        }
        plannedSnapshot = snapshot;
        path = List.copyOf(converted);
        transitions = result.transitions();
        pathIndex = joinIndex >= 0 ? Math.min(joinIndex + 1, path.size()) : Math.min(1, path.size());
        finalPlanningSegment = result.goalReached();
        lastTargetDistance = Double.POSITIVE_INFINITY; ticksWithoutProgress = 0;
        task.status = NavigationStatus.RUNNING;
    }

    /** Waits for ordinary chunk loading; this never requests world generation. */
    private void deferForFrontierChunks(int nodesVisited) {
        if (++frontierChunkWaitAttempts > MAX_FRONTIER_CHUNK_RETRIES) {
            task.fail("unreachable", nodesVisited);
            return;
        }
        waitingForFrontierChunks = true;
        int delay = Math.min(20, FRONTIER_CHUNK_RETRY_DELAY_TICKS * frontierChunkWaitAttempts);
        nextFrontierRetryTick = runtime.server().getTickCount() + delay;
        task.status = NavigationStatus.COMPUTING;
        owner.actions().stop();
    }

    private void finishSuccess() {
        // The arrival sphere may extend over the gap. Finish the active landing before stopping momentum.
        if (!stableForArrival(owner.player()) && activeTransition() != null) return;
        if (goal instanceof NavigationGoal.LookAtGoal) lookAt(goalPoint());
        owner.actions().stop();
        // A position tolerance can be met in mid-jump. Completing then lets
        // the next request plan from an airborne cell while sprint momentum
        // carries the bot off a narrow landing. Wait for a stable arrival.
        ServerPlayer player = owner.player();
        if (!(goal instanceof NavigationGoal.LookAtGoal) && !stableForArrival(player)) {
            Vec3 motion = player.getDeltaMovement();
            player.setDeltaMovement(0.0D, motion.y, 0.0D);
            waitingForLanding = true;
            return;
        }
        if (advanceSequence()) return;
        runtime.releaseNavigationTickets(owner.id());
        requestGeneration++;
        cancelSearchFuture();
        planningDeferred = false;
        waitingForFrontierChunks = false;
        frontierChunkWaitAttempts = 0;
        nextFrontierRetryTick = 0;
        int nodes = resultNodesVisited();
        long duration = System.nanoTime() - task.startedNanos;
        brakeHorizontal();
        path = List.of();
        placedActions.clear();
        task.complete(NavigationResult.success(nodes, duration));
    }

    private boolean stableForArrival(ServerPlayer player) {
        return player.onGround() || player.isInWater()
                || isClimbable((ServerLevel) player.level(), player.blockPosition());
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
        expandedSnapshotRadius = 0;
        finalPlanningSegment = false;
        lastTargetDistance = Double.POSITIVE_INFINITY;
        ticksWithoutProgress = 0;
        repathAttempts = 0;
        stallAnchor = null;
        ticksNearStallAnchor = 0;
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

    private void cancelNavigationMining() {
        MiningTask mining = navigationMining;
        navigationMining = null;
        if (mining != null && mining.status() == MiningStatus.RUNNING) mining.cancel();
    }

    private record PlacedAction(ResourceKey<Level> dimension, BlockPos position,
                                BlockState previousState, BlockState placedState,
                                int slot, ItemStack returnedStack) { }

    private final class NavigationTaskImpl implements NavigationTask {
        private volatile NavigationStatus status = NavigationStatus.IDLE;
        private final CompletableFuture<NavigationResult> completion = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final long startedNanos = System.nanoTime();
        private int nodesVisited;
        private final int startedTick = runtime.server().getTickCount();
        private final Vec3 initialPosition = owner.player().position();
        private Vec3 previousPosition = initialPosition;
        private float previousHealth = owner.player().getHealth();
        private float damageTaken;
        private int firstMovementTick = -1, terminalTick = -1, replans;
        private long reusedSnapshotCells, ticksWithoutMovement;
        void observe() {
            Vec3 position = owner.player().position();
            if (firstMovementTick < 0 && position.distanceToSqr(initialPosition) >= .0025) {
                firstMovementTick = runtime.server().getTickCount() - startedTick;
            }
            if (position.distanceToSqr(previousPosition) < .0001) ticksWithoutMovement++;
            else ticksWithoutMovement = 0;
            previousPosition = position;
            float health = owner.player().getHealth();
            damageTaken += Math.max(0, previousHealth - health);
            previousHealth = health;
        }
        @Override public NavigationDiagnostics diagnostics() {
            return new NavigationDiagnostics((terminalTick >= 0 ? terminalTick : runtime.server().getTickCount()) - startedTick,
                    firstMovementTick, replans, damageTaken, reusedSnapshotCells, completion.isDone(), ticksWithoutMovement);
        }
        @Override public NavigationStatus status() { return status; }
        @Override public CompletableFuture<NavigationResult> completion() { return completion; }
        @Override public void cancel() {
            NavigatorImpl.this.runtime.ensureServerThread();
            if (completion.isDone()) return;
            if (cancelled.compareAndSet(false, true)) {
                observe(); terminalTick = runtime.server().getTickCount();
                status = NavigationStatus.CANCELLED;
                if (NavigatorImpl.this.task == this) {
                    owner.actions().stop();
                    MiningTask mining = NavigatorImpl.this.navigationMining;
                    NavigatorImpl.this.navigationMining = null;
                    NavigatorImpl.this.requestGeneration++;
                    NavigatorImpl.this.planningDeferred = false;
                    NavigatorImpl.this.cancelSearchFuture();
                    NavigatorImpl.this.path = List.of();
                    NavigatorImpl.this.pathIndex = 0;
                    NavigatorImpl.this.goal = null;
                    NavigatorImpl.this.followPlanPosition = null;
                    NavigatorImpl.this.rollbackPlacedActions();
                    NavigatorImpl.this.runtime.releaseNavigationTickets(owner.id());
                    if (mining != null && mining.status() == MiningStatus.RUNNING) mining.cancel();
                }
                completion.complete(new NavigationResult(NavigationStatus.CANCELLED, "cancelled", nodesVisited,
                        System.nanoTime() - startedNanos));
            }
        }
        void fail(String reason, int nodes) {
            observe(); terminalTick = runtime.server().getTickCount();
            owner.actions().stop();
            NavigatorImpl.this.requestGeneration++;
            NavigatorImpl.this.cancelSearchFuture();
            NavigatorImpl.this.planningDeferred = false;
            path = List.of();
            pathIndex = 0;
            status = NavigationStatus.FAILED;
            NavigatorImpl.this.rollbackPlacedActions();
            NavigatorImpl.this.runtime.releaseNavigationTickets(owner.id());
            cancelNavigationMining();
            completion.complete(NavigationResult.failure(reason, nodesVisited, System.nanoTime() - startedNanos));
        }
        void stuck(int nodes) {
            observe(); terminalTick = runtime.server().getTickCount();
            owner.actions().stop();
            NavigatorImpl.this.requestGeneration++;
            NavigatorImpl.this.cancelSearchFuture();
            NavigatorImpl.this.planningDeferred = false;
            path = List.of();
            pathIndex = 0;
            status = NavigationStatus.STUCK;
            NavigatorImpl.this.rollbackPlacedActions();
            NavigatorImpl.this.runtime.releaseNavigationTickets(owner.id());
            cancelNavigationMining();
            completion.complete(NavigationResult.stuck(nodesVisited, System.nanoTime() - startedNanos));
        }
        void complete(NavigationResult result) {
            observe(); terminalTick = runtime.server().getTickCount();
            status = result.status(); completion.complete(result);
        }
    }
}
