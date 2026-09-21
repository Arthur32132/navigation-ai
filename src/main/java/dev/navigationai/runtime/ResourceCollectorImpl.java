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

    ResourceCollectorImpl(BotRuntime runtime, BotHandleImpl owner) {
        this.runtime = runtime;
        this.owner = owner;
    }

    @Override
    public GatherTask gather(GatherRequest request, GatherPolicy policy) {
        runtime.ensureServerThread();
        cancel();
        owner.navigator().cancel();
        owner.minerImpl().cancel();
        task = new GatherTaskImpl(request, policy);
        return task;
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
        if (current.candidates.isEmpty()) {
            ServerPlayer player = owner.player();
            BlockPos origin = player.blockPosition();
            int radius = current.policy.radius();
            List<BlockPos> found = new ArrayList<>();
            for (int x = origin.getX() - radius; x <= origin.getX() + radius; x++) {
                for (int y = origin.getY() - radius; y <= origin.getY() + radius; y++) {
                    for (int z = origin.getZ() - radius; z <= origin.getZ() + radius; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (level.hasChunkAt(pos) && current.request.blocks().contains(level.getBlockState(pos).getBlock())) found.add(pos);
                    }
                }
            }
            found.sort(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
            current.candidates.addAll(found);
        }
        while (current.candidateIndex < current.candidates.size()) {
            BlockPos target = current.candidates.get(current.candidateIndex++);
            if (!current.request.blocks().contains(level.getBlockState(target).getBlock())) continue;
            List<BlockPos> standingPositions = findStandingPositions(level, target);
            if (standingPositions.isEmpty()) continue;
            current.target = target;
            current.standingPositions = standingPositions;
            current.standingIndex = 0;
            current.attemptsForBlock = 0;
            startNavigation(current, current.standingPositions.get(current.standingIndex++));
            current.attemptsForBlock++;
            return;
        }
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
        } else if (status == NavigationStatus.FAILED || status == NavigationStatus.CANCELLED) {
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
        private final List<BlockPos> candidates = new ArrayList<>();
        private final long started = System.nanoTime();
        private GatherStatus status = GatherStatus.RUNNING;
        private GatherPhase phase = GatherPhase.SEARCHING;
        private BlockPos target;
        private BlockPos stand;
        private List<BlockPos> standingPositions = List.of();
        private NavigationTask navigation;
        private MiningTask mining;
        private int candidateIndex;
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
            if (cancelled.compareAndSet(false, true) && status == GatherStatus.RUNNING) {
                if (navigation != null) navigation.cancel();
                if (mining != null) mining.cancel();
                status = GatherStatus.CANCELLED;
                phase = GatherPhase.DONE;
                completion.complete(new GatherResult(status, "cancelled", collected, brokenBlocks, System.nanoTime() - started));
            }
        }
        private void complete(String reason) {
            if (status != GatherStatus.RUNNING) return;
            status = GatherStatus.SUCCESS;
            phase = GatherPhase.DONE;
            completion.complete(new GatherResult(status, reason, collected, brokenBlocks, System.nanoTime() - started));
        }
        private void fail(String reason) {
            if (status != GatherStatus.RUNNING) return;
            if (navigation != null) navigation.cancel();
            if (mining != null) mining.cancel();
            status = GatherStatus.FAILED;
            phase = GatherPhase.DONE;
            completion.complete(new GatherResult(status, reason, collected, brokenBlocks, System.nanoTime() - started));
        }
    }
}
