package dev.navigationai.runtime;

import dev.navigationai.api.GatherPhase;
import dev.navigationai.api.GatherPolicy;
import dev.navigationai.api.GatherRequest;
import dev.navigationai.api.GatherResult;
import dev.navigationai.api.GatherStatus;
import dev.navigationai.api.GatherTask;
import dev.navigationai.api.NavigationGoal;
import dev.navigationai.api.NavigationPolicy;
import dev.navigationai.api.NavigationStatus;
import dev.navigationai.api.NavigationTask;
import dev.navigationai.api.ResourceCollector;
import dev.navigationai.api.MiningResult;
import dev.navigationai.api.MiningStatus;
import dev.navigationai.api.MiningTask;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class ResourceCollectorImpl implements ResourceCollector {
    private final BotRuntime runtime;
    private final BotHandleImpl owner;
    private GatherTaskImpl task;
    private long startGeneration;

    ResourceCollectorImpl(BotRuntime runtime, BotHandleImpl owner) {
        this.runtime = runtime;
        this.owner = owner;
    }

    @Override
    public GatherTask gather(GatherRequest request, GatherPolicy policy) {
        runtime.ensureServerThread();
        owner.ensureActive();
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(policy, "policy");
        long generation = ++startGeneration;
        cancel();
        if (generation != startGeneration) return superseded(request, policy);
        owner.navigator().cancel();
        if (generation != startGeneration) return superseded(request, policy);
        owner.minerImpl().cancel();
        if (generation != startGeneration) return superseded(request, policy);
        owner.ensureActive();
        task = new GatherTaskImpl(request, policy);
        return task;
    }

    private GatherTask superseded(GatherRequest request, GatherPolicy policy) {
        GatherTaskImpl cancelled = new GatherTaskImpl(request, policy);
        cancelled.cancel();
        return cancelled;
    }

    @Override public GatherStatus status() { return task == null ? GatherStatus.IDLE : task.status; }
    @Override public GatherTask task() { return task; }

    void tick() {
        if (task == null || task.status != GatherStatus.RUNNING) return;
        task.ticks++;
        if (task.ticks > task.policy.timeoutTicks()) { task.fail("timeout"); return; }
        if (task.collected >= task.request.count()) { task.complete("target_reached"); return; }

        switch (task.phase) {
            case SEARCHING -> search(task);
            case MOVING -> tickMoving(task);
            case MINING -> tickMining(task);
            case COLLECTING -> tickCollecting(task);
            case DONE -> { }
        }
    }

    void cancel() {
        runtime.ensureServerThread();
        if (task != null && task.status == GatherStatus.RUNNING) task.cancel();
    }

    private void search(GatherTaskImpl current) {
        ServerLevel level = (ServerLevel) owner.player().level();
        if (current.target != null) {
            if (!current.request.blocks().contains(level.getBlockState(current.target).getBlock())) {
                clearTarget(current);
            } else if (current.standingIndex < current.standingPositions.size()
                    && current.attemptsForBlock < current.policy.maxAttemptsPerBlock()) {
                startNavigation(current, current.standingPositions.get(current.standingIndex++));
                current.attemptsForBlock++;
                return;
            } else {
                clearTarget(current);
            }
        }
        if (!current.scanComplete) {
            int radius = current.policy.radius();
            if (current.scanOrigin == null) current.scanOrigin = owner.player().blockPosition();
            BlockPos origin = current.scanOrigin;
            int side = radius * 2 + 1;
            int volume = side * side * side;
            int cells = runtime.acquireResourceScanCells(512);
            long deadline = runtime.planningDeadline();
            for (int scanned = 0; scanned < cells && current.scanIndex < volume; scanned++) {
                if (System.nanoTime() >= deadline) break;
                int index = current.scanIndex++;
                BlockPos pos = origin.offset(index / (side * side) - radius,
                        index / side % side - radius, index % side - radius);
                if (level.hasChunkAt(pos) && current.request.blocks().contains(level.getBlockState(pos).getBlock())) {
                    current.candidates.add(pos);
                }
            }
            if (current.scanIndex < volume) return;
            current.scanComplete = true;
        }
        int examined = 0;
        long deadline = runtime.planningDeadline();
        while (!current.candidates.isEmpty() && examined++ < 512 && System.nanoTime() < deadline) {
            BlockPos target = current.candidates.remove();
            if (!current.request.blocks().contains(level.getBlockState(target).getBlock())) continue;
            List<BlockPos> standingPositions = findStandingPositions(level, target);
            if (standingPositions.isEmpty()) continue;
            if (!runtime.reserveResource(level, target, current)) continue;
            current.target = target;
            current.standingPositions = standingPositions;
            current.standingIndex = 0;
            current.attemptsForBlock = 0;
            startNavigation(current, current.standingPositions.get(current.standingIndex++));
            current.attemptsForBlock++;
            return;
        }
        if (!current.candidates.isEmpty()) return;
        current.fail(current.brokenBlocks == 0
                ? (current.lastFailureReason == null ? "no_resources" : current.lastFailureReason)
                : "resources_exhausted");
    }

    private void startNavigation(GatherTaskImpl current, BlockPos stand) {
        current.stand = stand;
        current.phase = GatherPhase.MOVING;
        current.navigation = owner.navigator().goTo(
                new NavigationGoal.PositionGoal(Vec3.atCenterOf(stand), 1.0),
                new NavigationPolicy(false, false, true, false, 10_000, 256,
                        Math.max(4, current.policy.radius()), 0));
    }

    private void clearTarget(GatherTaskImpl current) {
        runtime.releaseResources(current);
        current.target = null;
        current.stand = null;
        current.standingPositions = List.of();
        current.standingIndex = 0;
        current.attemptsForBlock = 0;
        current.navigation = null;
        current.mining = null;
    }

    private void tickMoving(GatherTaskImpl current) {
        if (current.navigation == null) { current.phase = GatherPhase.SEARCHING; return; }
        NavigationStatus status = current.navigation.status();
        if (status == NavigationStatus.SUCCESS) {
            current.mining = owner.miner().breakBlock(current.target);
            current.phase = GatherPhase.MINING;
        } else if (status == NavigationStatus.FAILED || status == NavigationStatus.CANCELLED
                || status == NavigationStatus.STUCK) {
            current.phase = GatherPhase.SEARCHING;
        }
    }

    private void tickMining(GatherTaskImpl current) {
        if (current.mining == null) { current.phase = GatherPhase.SEARCHING; return; }
        MiningStatus status = current.mining.status();
        if (status == MiningStatus.SUCCESS) {
            current.brokenBlocks++;
            current.collectingTicks = 0;
            current.phase = GatherPhase.COLLECTING;
        } else if (status == MiningStatus.FAILED || status == MiningStatus.CANCELLED) {
            var result = current.mining.completion().getNow(null);
            current.lastFailureReason = result == null
                    ? status.name().toLowerCase(java.util.Locale.ROOT)
                    : result.reason();
            current.phase = GatherPhase.SEARCHING;
        }
    }

    private void tickCollecting(GatherTaskImpl current) {
        current.collectingTicks++;
        collectNearbyDrops(current.request.items());
        current.collected = countItem(owner.player(), current.request.items()) - current.baseline;
        if (current.collected >= current.request.count()) { current.complete("target_reached"); return; }
        if (current.collectingTicks >= 20) {
            if (!hasInventoryRoom(owner.player(), current.request.items())) current.fail("inventory_full");
            else {
                clearTarget(current);
                current.phase = GatherPhase.SEARCHING;
            }
        }
    }

    private void collectNearbyDrops(java.util.Set<net.minecraft.world.item.Item> items) {
        ServerPlayer player = owner.player();
        ServerLevel level = (ServerLevel) player.level();
        AABB area = player.getBoundingBox().inflate(2.5);
        for (net.minecraft.world.entity.Entity entity : level.getEntities(player, area, candidate ->
                candidate instanceof ItemEntity item && items.contains(item.getItem().getItem()))) {
            ((ItemEntity) entity).playerTouch(player);
        }
    }

    private static int countItem(ServerPlayer player, java.util.Set<net.minecraft.world.item.Item> items) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (items.contains(stack.getItem())) count += stack.getCount();
        }
        return count;
    }

    private static boolean hasInventoryRoom(ServerPlayer player, java.util.Set<net.minecraft.world.item.Item> items) {
        for (net.minecraft.world.item.Item item : items) {
            ItemStack probe = new ItemStack(item);
            if (player.getInventory().getSlotWithRemainingSpace(probe) >= 0
                    || player.getInventory().getFreeSlot() >= 0) return true;
        }
        return false;
    }

    private static List<BlockPos> findStandingPositions(ServerLevel level, BlockPos target) {
        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos candidate : new BlockPos[] {
                target.west(), target.east(), target.north(), target.south(), target.above() }) {
            BlockState feet = level.getBlockState(candidate);
            BlockState head = level.getBlockState(candidate.above());
            BlockState below = level.getBlockState(candidate.below());
            if (feet.isAir() && head.isAir() && !below.isAir()) positions.add(candidate);
        }
        return List.copyOf(positions);
    }

    private final class GatherTaskImpl implements GatherTask {
        private final GatherRequest request;
        private final GatherPolicy policy;
        private final CompletableFuture<GatherResult> completion = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private BlockPos scanOrigin;
        private final java.util.PriorityQueue<BlockPos> candidates = new java.util.PriorityQueue<>(
                Comparator.<BlockPos>comparingDouble(pos -> pos.distSqr(scanOrigin))
                        .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ));
        private int scanIndex;
        private boolean scanComplete;
        private final long started = System.nanoTime();
        private GatherStatus status = GatherStatus.RUNNING;
        private GatherPhase phase = GatherPhase.SEARCHING;
        private BlockPos target;
        private BlockPos stand;
        private List<BlockPos> standingPositions = List.of();
        private NavigationTask navigation;
        private MiningTask mining;
        private int standingIndex;
        private int attemptsForBlock;
        private int ticks;
        private int collectingTicks;
        private int baseline;
        private int collected;
        private int brokenBlocks;
        private String lastFailureReason;

        private GatherTaskImpl(GatherRequest request, GatherPolicy policy) {
            this.request = request;
            this.policy = policy;
            this.baseline = countItem(owner.player(), request.items());
        }
        @Override public GatherStatus status() { return status; }
        @Override public GatherPhase phase() { return phase; }
        @Override public int collectedItems() { return collected; }
        @Override public CompletableFuture<GatherResult> completion() { return completion; }
        @Override public void cancel() {
            runtime.ensureServerThread();
            if (cancelled.compareAndSet(false, true) && status == GatherStatus.RUNNING) {
                status = GatherStatus.CANCELLED;
                phase = GatherPhase.DONE;
                runtime.releaseResources(this);
                if (navigation != null) navigation.cancel();
                if (mining != null) mining.cancel();
                completion.complete(new GatherResult(status, "cancelled", collected, brokenBlocks, System.nanoTime() - started));
            }
        }
        private void complete(String reason) {
            if (status != GatherStatus.RUNNING) return;
            status = GatherStatus.SUCCESS;
            phase = GatherPhase.DONE;
            runtime.releaseResources(this);
            completion.complete(new GatherResult(status, reason, collected, brokenBlocks, System.nanoTime() - started));
        }
        private void fail(String reason) {
            if (status != GatherStatus.RUNNING) return;
            status = GatherStatus.FAILED;
            phase = GatherPhase.DONE;
            runtime.releaseResources(this);
            if (navigation != null) navigation.cancel();
            if (mining != null) mining.cancel();
            completion.complete(new GatherResult(status, reason, collected, brokenBlocks, System.nanoTime() - started));
        }
    }
}
