package dev.navigationai.runtime;

import dev.navigationai.api.ExcavationPhase;
import dev.navigationai.api.ExcavationPolicy;
import dev.navigationai.api.ExcavationRequest;
import dev.navigationai.api.ExcavationResult;
import dev.navigationai.api.ExcavationStatus;
import dev.navigationai.api.ExcavationTask;
import dev.navigationai.api.MiningStatus;
import dev.navigationai.api.MiningTask;
import dev.navigationai.api.NavigationGoal;
import dev.navigationai.api.NavigationPolicy;
import dev.navigationai.api.NavigationStatus;
import dev.navigationai.api.NavigationTask;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/** Mines a short face, walks into it, then repeats. Never removes the player's floor. */
final class ExcavatorImpl {
    private final BotRuntime runtime;
    private final BotHandleImpl owner;
    private final MinerImpl miner;
    private ExcavationTaskImpl task;
    private static final NavigationPolicy WALK = new NavigationPolicy(false, false, false, false,
            20_000, 1_024, 16, 100.0, 1);

    ExcavatorImpl(BotRuntime runtime, BotHandleImpl owner, MinerImpl miner) {
        this.runtime = runtime; this.owner = owner; this.miner = miner;
    }

    ExcavationTask start(ExcavationRequest request, ExcavationPolicy policy) {
        task = new ExcavationTaskImpl(request, policy);
        return task;
    }

    ExcavationTask superseded(ExcavationRequest request, ExcavationPolicy policy) {
        ExcavationTaskImpl cancelled = new ExcavationTaskImpl(request, policy);
        cancelled.cancel();
        return cancelled;
    }

    ExcavationStatus status() { return task == null ? ExcavationStatus.IDLE : task.status; }
    ExcavationTask task() { return task; }
    void cancel() { if (task != null && task.status == ExcavationStatus.RUNNING) task.cancel(); }

    void tick() {
        ExcavationTaskImpl current = task;
        if (current == null || current.status != ExcavationStatus.RUNNING) return;
        if (!owner.entity().level().dimension().equals(current.dimension)) {
            current.finish(ExcavationStatus.FAILED, "different_dimension"); return;
        }
        current.syncProgress();
        if (++current.ticks > current.policy.timeoutTicks()) { current.finish(ExcavationStatus.FAILED, "timeout"); return; }
        if (current.policy.collectDrops()) collectDrops();
        switch (current.phase) {
            case APPROACHING -> approach(current);
            case CHECKING -> checkFace(current);
            case MINING -> mineFace(current);
            case ADVANCING -> advance(current);
            case RETURNING -> returnToEntrance(current);
            case DONE -> { }
        }
    }

    private void approach(ExcavationTaskImpl current) {
        BlockPos start = current.request.positionAt(current.completed);
        if (current.navigation == null) {
            if (!safeStand(start)) { current.finish(ExcavationStatus.FAILED, "unsafe_entrance"); return; }
            current.navigation = navigate(start);
        }
        if (current.navigation.status() == NavigationStatus.SUCCESS) {
            current.navigation = null;
            current.phase = ExcavationPhase.CHECKING;
        } else if (navigationFailed(current.navigation)) current.finish(ExcavationStatus.FAILED, "entrance_unreachable");
    }

    private void checkFace(ExcavationTaskImpl current) {
        if (current.completed >= current.request.length()) { retreat(current, ExcavationStatus.SUCCESS, "excavated"); return; }
        BlockPos next = current.request.positionAt(current.completed + 1);
        ServerLevel level = (ServerLevel)owner.entity().level();
        if (!MiningSafety.loaded(level, next) || !MiningSafety.loaded(level, next.above(3))) {
            retreat(current, ExcavationStatus.FAILED, "unloaded_chunk"); return;
        }
        if (next.getY() <= level.getMinY() || next.getY() + 3 >= level.getMaxY()) {
            retreat(current, ExcavationStatus.FAILED, "world_bounds"); return;
        }
        if (!level.getBlockState(next.below()).isCollisionShapeFullBlock(level, next.below())
                || NavigationSafety.dangerous(level, next) || !level.getFluidState(next.below()).isEmpty()) {
            retreat(current, ExcavationStatus.FAILED, "unsafe_floor"); return;
        }
        // Check the entire face before altering any of it; a hidden container or fluid is protected.
        for (BlockPos position : current.request.clearanceAt(current.completed + 1)) {
            String danger = MiningSafety.excavationFailure(level, owner.entity(), position);
            if (danger != null) { retreat(current, ExcavationStatus.FAILED, danger); return; }
        }
        for (BlockPos position : current.request.clearanceAt(current.completed + 1)) {
            var state = level.getBlockState(position);
            if (state.isAir()) continue;
            if (current.broken >= current.policy.maxBlocks()) { retreat(current, ExcavationStatus.FAILED, "block_budget_exhausted"); return; }
            if (!miner.hasUsableTool(state, current.policy.minRemainingDurability())) {
                retreat(current, ExcavationStatus.FAILED, "no_suitable_tool"); return;
            }
            if (current.policy.collectDrops() && !hasInventoryCapacity()) {
                retreat(current, ExcavationStatus.FAILED, "inventory_full"); return;
            }
            if (!runtime.reserveResource(level, position, current)) { retreat(current, ExcavationStatus.FAILED, "resource_reserved"); return; }
            current.target = position;
            current.mining = miner.breakBlockForExcavator(position, current.policy.minRemainingDurability(), current.policy.collectDrops());
            if (task == current && current.status == ExcavationStatus.RUNNING) current.phase = ExcavationPhase.MINING;
            return;
        }
        if (!safeStand(next)) { retreat(current, ExcavationStatus.FAILED, "unsafe_passage"); return; }
        current.navigation = navigate(next);
        if (task == current && current.status == ExcavationStatus.RUNNING) current.phase = ExcavationPhase.ADVANCING;
    }

    private void mineFace(ExcavationTaskImpl current) {
        if (current.mining == null) { retreat(current, ExcavationStatus.FAILED, "mining_missing"); return; }
        if (current.mining.status() == MiningStatus.RUNNING) {
            // Terrain and permissions may change after the initial face check.
            String danger = MiningSafety.excavationFailure((ServerLevel)owner.entity().level(), owner.entity(), current.target);
            if (danger != null) { current.mining.cancel(); retreat(current, ExcavationStatus.FAILED, danger); }
            return;
        }
        runtime.releaseResources(current);
        if (current.mining.status() == MiningStatus.SUCCESS) {
            current.broken++;
            current.mining = null;
            current.target = null;
            current.phase = ExcavationPhase.CHECKING;
        } else {
            var result = current.mining.completion().getNow(null);
            retreat(current, ExcavationStatus.FAILED, result == null ? "mining_failed" : "mining_" + result.reason());
        }
    }

    private void advance(ExcavationTaskImpl current) {
        BlockPos next = current.request.positionAt(current.completed + 1);
        if (!safeStand(next)) { retreat(current, ExcavationStatus.FAILED, "unsafe_passage"); return; }
        if (current.navigation.status() == NavigationStatus.SUCCESS) {
            current.completed++;
            current.navigation = null;
            current.phase = ExcavationPhase.CHECKING;
        } else if (navigationFailed(current.navigation)) retreat(current, ExcavationStatus.FAILED, "passage_unreachable");
    }

    private void retreat(ExcavationTaskImpl current, ExcavationStatus outcome, String reason) {
        runtime.releaseResources(current);
        current.outcome = outcome;
        current.reason = reason;
        if (current.mining != null) current.mining.cancel();
        current.mining = null;
        if (current.navigation != null) current.navigation.cancel();
        current.navigation = null;
        if (task != current || current.status != ExcavationStatus.RUNNING) return;
        if (current.policy.returnToEntrance() && safeStand(current.request.origin())) {
            current.phase = ExcavationPhase.RETURNING;
            current.navigation = navigate(current.request.origin());
        } else current.finish(outcome, reason);
    }

    private void returnToEntrance(ExcavationTaskImpl current) {
        if (current.navigation.status() == NavigationStatus.SUCCESS) {
            current.returned = true;
            current.finish(current.outcome, current.reason);
        } else if (navigationFailed(current.navigation)) current.finish(ExcavationStatus.FAILED, current.reason + "_return_failed");
    }

    private NavigationTask navigate(BlockPos position) {
        return owner.navigatorImpl().goToAsChild(new NavigationGoal.PositionGoal(new Vec3(position.getX() + .5,
                position.getY() + NavigationGeometry.feetOffset((ServerLevel)owner.entity().level(), position),
                position.getZ() + .5), .2), WALK);
    }

    private boolean safeStand(BlockPos position) {
        ServerLevel level = (ServerLevel)owner.entity().level();
        if (!WorldSnapshot.loadedGeometryAt(level, position.getX(), position.getZ())) return false;
        if (!level.getWorldBorder().isWithinBounds(position)) return false;
        if (!level.getBlockState(position.below()).isCollisionShapeFullBlock(level, position.below())
                || NavigationSafety.dangerous(level, position)) return false;
        if (!level.getFluidState(position).isEmpty() || !level.getFluidState(position.above()).isEmpty()) return false;
        var body = owner.entity().getDimensions(Pose.STANDING);
        return NavigationGeometry.bodyFits(level, position, NavigationGeometry.feetOffset(level, position),
                body.width(), body.height(), false);
    }

    private boolean hasInventoryCapacity() {
        var inventory = ActorSupport.context(owner.entity()).getInventory();
        if (inventory.getFreeSlot() >= 0) return true;
        return inventory.getNonEquipmentItems().stream().anyMatch(stack -> stack.getCount() < stack.getMaxStackSize());
    }

    private void collectDrops() {
        var player = owner.entity();
        for (var entity : player.level().getEntities(player, player.getBoundingBox().inflate(1.5), entity -> entity instanceof ItemEntity)) {
            ((ItemEntity)entity).playerTouch(ActorSupport.context(player));
        }
    }

    private static boolean navigationFailed(NavigationTask navigation) {
        return navigation.status() == NavigationStatus.FAILED || navigation.status() == NavigationStatus.STUCK
                || navigation.status() == NavigationStatus.CANCELLED;
    }

    private final class ExcavationTaskImpl implements ExcavationTask {
        final ExcavationRequest request;
        final ExcavationPolicy policy;
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        final long started = System.nanoTime();
        final CompletableFuture<ExcavationResult> completion = new CompletableFuture<>();
        ExcavationStatus status = ExcavationStatus.RUNNING;
        ExcavationPhase phase = ExcavationPhase.APPROACHING;
        ExcavationStatus outcome;
        String reason;
        int completed, broken, ticks;
        boolean returned;
        BlockPos target;
        MiningTask mining;
        NavigationTask navigation;

        ExcavationTaskImpl(ExcavationRequest request, ExcavationPolicy policy) {
            this.request = request; this.policy = policy; completed = request.completedSegments();
            dimension = owner.entity().level().dimension();
        }
        @Override public ExcavationStatus status() { return status; }
        @Override public ExcavationPhase phase() { return phase; }
        @Override public int completedSegments() {
            return completed + (phase == ExcavationPhase.ADVANCING && navigation != null
                    && navigation.status() == NavigationStatus.SUCCESS ? 1 : 0);
        }
        @Override public int brokenBlocks() {
            return broken + (mining != null && mining.status() == MiningStatus.SUCCESS ? 1 : 0);
        }
        @Override public CompletableFuture<ExcavationResult> completion() { return completion.copy(); }
        @Override public void cancel() { runtime.ensureServerThread(); finish(ExcavationStatus.CANCELLED, "cancelled"); }

        void finish(ExcavationStatus outcome, String reason) {
            if (status != ExcavationStatus.RUNNING) return;
            syncProgress();
            status = outcome; phase = ExcavationPhase.DONE;
            runtime.releaseResources(this);
            if (task == this) runtime.releaseResourceDrops(owner.id());
            if (mining != null) mining.cancel();
            if (navigation != null) navigation.cancel();
            completion.complete(new ExcavationResult(status, reason, completed, broken, returned, System.nanoTime() - started));
        }

        // Child controllers finish later in the bot tick. Save/cancel/getters must
        // still report that physical progress before the next excavation tick.
        void syncProgress() {
            if (status != ExcavationStatus.RUNNING) return;
            if (mining != null && mining.status() == MiningStatus.SUCCESS) {
                broken++;
                mining = null;
                target = null;
                runtime.releaseResources(this);
                if (phase == ExcavationPhase.MINING) phase = ExcavationPhase.CHECKING;
            }
            if (phase == ExcavationPhase.ADVANCING && navigation != null && navigation.status() == NavigationStatus.SUCCESS) {
                completed++;
                navigation = null;
                phase = ExcavationPhase.CHECKING;
            }
        }
    }
}
