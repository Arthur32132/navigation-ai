package dev.navigationai.runtime;

import dev.navigationai.api.NavigationGoal;
import dev.navigationai.api.NavigationPolicy;
import dev.navigationai.api.NavigationResult;
import dev.navigationai.api.NavigationStatus;
import dev.navigationai.api.NavigationTask;
import dev.navigationai.api.Navigator;
import dev.navigationai.navigation.GridPath;
import dev.navigationai.navigation.GridPathfinder;
import dev.navigationai.navigation.GridPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

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
        if (task != null && task.status() != NavigationStatus.SUCCESS && task.status() != NavigationStatus.FAILED) task.cancel();
        owner.actions().stop();
        path = List.of(); pathIndex = 0; goal = null; followPlanPosition = null;
    }
    @Override public NavigationStatus status() { return task == null ? NavigationStatus.IDLE : task.status(); }
    @Override public Optional<NavigationGoal> goal() { return Optional.ofNullable(goal); }

    void tick() {
        if (task == null || goal == null || task.status() == NavigationStatus.CANCELLED || task.status() == NavigationStatus.FAILED || task.status() == NavigationStatus.SUCCESS) return;
        if (goal instanceof NavigationGoal.FollowGoal follow && shouldRepathFollow(follow)) {
            if (task.status() == NavigationStatus.RUNNING) requestPath();
        }
        if (task.status() != NavigationStatus.RUNNING || path.isEmpty()) return;
        if (pathIndex >= path.size()) { finishSuccess(); return; }
        BlockPos next = path.get(pathIndex);
        Vec3 target = Vec3.atCenterOf(next);
        double dx = target.x - owner.player().getX();
        double dz = target.z - owner.player().getZ();
        if (dx * dx + dz * dz < 0.14 && Math.abs(target.y - owner.player().getY()) < 1.1) {
            pathIndex++;
            if (pathIndex >= path.size()) { finishSuccess(); return; }
            next = path.get(pathIndex);
            target = Vec3.atCenterOf(next);
            dx = target.x - owner.player().getX(); dz = target.z - owner.player().getZ();
        }
        float yaw = (float)(Math.atan2(-dx, dz) * 180.0 / Math.PI);
        owner.actions().look(yaw, 0);
        boolean jump = next.getY() > owner.player().blockPosition().getY() && owner.player().onGround();
        owner.actions().setMovement(0, 1, jump, true, false);
        Vec3 current = owner.player().position();
        if (lastPosition != null && current.distanceToSqr(lastPosition) < 0.0004) ticksWithoutProgress++; else ticksWithoutProgress = 0;
        lastPosition = current;
        if (ticksWithoutProgress > 40) { task.status = NavigationStatus.COMPUTING; requestPath(); }
    }

    private void requestPath() {
        if (task == null || task.cancelled.get()) return;
        task.status = NavigationStatus.COMPUTING;
        path = List.of(); pathIndex = 0;
        ServerPlayer player = owner.player();
        ServerLevel level = (ServerLevel)player.level();
        if (goal instanceof NavigationGoal.FollowGoal follow) followPlanPosition = follow.target().position();
        BlockPos target = targetBlock();
        GridPos startPos = new GridPos(player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ());
        GridPos goalPos = new GridPos(target.getX(), target.getY(), target.getZ());
        WorldSnapshot snapshot = WorldSnapshot.capture(level, startPos, goalPos, policy.snapshotRadius(), policy.allowWater());
        long started = System.nanoTime();
        pathStartedNanos = started;
        long generation = ++requestGeneration;
        runtime.pathExecutor().submit(() -> {
            GridPath result = GridPathfinder.find(snapshot, startPos, goalPos, policy);
            runtime.server().execute(() -> applyPath(result, started, generation));
        });
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

    private void applyPath(GridPath result, long started, long generation) {
        if (task == null || task.cancelled.get() || goal == null) return;
        if (generation != requestGeneration) return;
        if (result.nodes().isEmpty()) { task.fail("unreachable", result.nodesVisited()); return; }
        ArrayList<BlockPos> converted = new ArrayList<>(result.nodes().size());
        for (GridPos pos : result.nodes()) converted.add(new BlockPos(pos.x(), pos.y(), pos.z()));
        path = List.copyOf(converted); pathIndex = Math.min(1, path.size());
        lastPosition = owner.player().position(); ticksWithoutProgress = 0; task.status = NavigationStatus.RUNNING;
    }

    private void finishSuccess() {
        owner.actions().stop();
        task.complete(NavigationResult.success(path.size(), System.nanoTime() - pathStartedNanos));
        path = List.of();
    }

    private final class NavigationTaskImpl implements NavigationTask {
        private volatile NavigationStatus status = NavigationStatus.IDLE;
        private final CompletableFuture<NavigationResult> completion = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        @Override public NavigationStatus status() { return status; }
        @Override public CompletableFuture<NavigationResult> completion() { return completion; }
        @Override public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                status = NavigationStatus.CANCELLED;
                completion.complete(new NavigationResult(NavigationStatus.CANCELLED, "cancelled", 0, 0));
                if (NavigatorImpl.this.task == this) {
                    owner.actions().stop();
                    NavigatorImpl.this.requestGeneration++;
                    NavigatorImpl.this.path = List.of();
                    NavigatorImpl.this.pathIndex = 0;
                    NavigatorImpl.this.goal = null;
                    NavigatorImpl.this.followPlanPosition = null;
                }
            }
        }
        void fail(String reason, int nodes) {
            status = NavigationStatus.FAILED;
            completion.complete(NavigationResult.failure(reason, nodes, System.nanoTime() - pathStartedNanos));
        }
        void complete(NavigationResult result) { status = result.status(); completion.complete(result); }
    }
}
