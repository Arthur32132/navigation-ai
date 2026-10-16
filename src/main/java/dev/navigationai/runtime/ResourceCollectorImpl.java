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
import net.minecraft.world.entity.LivingEntity;
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
        java.util.Objects.requireNonNull(policy, "policy");
        return gather(request, policy, new NavigationPolicy(false, false, true, false, 10_000, 256,
                Math.max(4, policy.radius()), 100));
    }

    @Override
    public GatherTask gather(GatherRequest request, GatherPolicy policy, NavigationPolicy navigationPolicy) {
        return startGather(request, policy, navigationPolicy, true);
    }

    GatherTask gatherAsChild(GatherRequest request, GatherPolicy policy, NavigationPolicy navigationPolicy) {
        return startGather(request, policy, navigationPolicy, false);
    }

    private GatherTask startGather(GatherRequest request, GatherPolicy policy, NavigationPolicy navigationPolicy,
                                   boolean root) {
        runtime.ensureServerThread();
        owner.ensureActive();
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(policy, "policy");
        java.util.Objects.requireNonNull(navigationPolicy, "navigationPolicy");
        if (root && owner.control().claim(BotControl.Kind.GATHER) < 0) return superseded(request, policy, navigationPolicy);
        long generation = ++startGeneration;
        long controlRequest = owner.control().generation();
        cancel();
        if (generation != startGeneration || !owner.control().current(controlRequest)) return superseded(request, policy, navigationPolicy);
        owner.navigator().cancel();
        if (generation != startGeneration || !owner.control().current(controlRequest)) return superseded(request, policy, navigationPolicy);
        owner.minerImpl().cancel();
        if (generation != startGeneration || !owner.control().current(controlRequest)) return superseded(request, policy, navigationPolicy);
        owner.ensureActive();
        task = new GatherTaskImpl(request, policy, navigationPolicy);
        return task;
    }

    private GatherTask superseded(GatherRequest request, GatherPolicy policy, NavigationPolicy navigationPolicy) {
        GatherTaskImpl cancelled = new GatherTaskImpl(request, policy, navigationPolicy);
        cancelled.cancel();
        return cancelled;
    }

    @Override public GatherStatus status() { return task == null ? GatherStatus.IDLE : task.status; }
    @Override public GatherTask task() { return task; }

    void tick() {
        if (task == null || task.status != GatherStatus.RUNNING) return;
        task.ticks++;
        if (task.ticks > task.policy.timeoutTicks()) { task.fail("timeout"); return; }
        if (task.phase != GatherPhase.MINING) {
            collectNearbyDrops(task.request.items());
            task.collected = countItem(owner.entity(), task.request.items()) - task.baseline;
            if (!hasInventoryRoom(owner.entity(), task.request.items()) && task.collected < task.request.count()) {
                task.fail("inventory_full"); return;
            }
        }
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
        ServerLevel level = (ServerLevel) owner.entity().level();
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
            if (current.scanOrigin == null) current.scanOrigin = owner.entity().blockPosition();
            BlockPos origin = current.scanOrigin;
            int cells = runtime.acquireResourceScanCells(512);
            long deadline = runtime.planningDeadline();
            for (int scanned = 0; scanned < cells && current.scanCursor.hasNext(); scanned++) {
                if (System.nanoTime() >= deadline) break;
                BlockPos pos = current.scanCursor.next(origin);
                current.scanIndex++;
                var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                if (chunk != null && current.request.blocks().contains(chunk.getBlockState(pos).getBlock())) {
                    current.candidates.add(pos);
                }
            }
            current.scanComplete = !current.scanCursor.hasNext();
        }
        int examined = 0;
        long deadline = runtime.planningDeadline();
        while ((!current.veinCandidates.isEmpty() || !current.candidates.isEmpty())
                && examined++ < 512 && System.nanoTime() < deadline) {
            // Follow newly exposed neighbours before returning to the original area scan.
            BlockPos target = current.veinCandidates.isEmpty() ? current.candidates.remove() : current.veinCandidates.removeFirst();
            current.veinQueued.remove(target);
            var chunk = level.getChunkSource().getChunkNow(target.getX() >> 4, target.getZ() >> 4);
            if (chunk == null || !current.request.blocks().contains(chunk.getBlockState(target).getBlock())) continue;
            String unsafe = MiningSafety.excavationFailure(level, owner.entity(), target);
            if (unsafe != null) { current.lastFailureReason = unsafe; continue; }
            if (!owner.minerImpl().hasUsableTool(level.getBlockState(target), 1)) {
                current.lastFailureReason = "no_suitable_tool"; continue;
            }
            List<BlockPos> standingPositions = findStandingPositions(level, owner.entity(), target,
                    current.navigationPolicy);
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
        if (!current.veinCandidates.isEmpty() || !current.candidates.isEmpty() || !current.scanComplete) return;
        current.fail(current.brokenBlocks == 0
                ? (current.lastFailureReason == null ? "no_resources" : current.lastFailureReason)
                : "resources_exhausted");
    }

    private void startNavigation(GatherTaskImpl current, BlockPos stand) {
        current.stand = stand;
        current.phase = GatherPhase.MOVING;
        current.navigation = owner.navigatorImpl().goToAsChild(
                new NavigationGoal.PositionGoal(new Vec3(stand.getX()+.5,
                        stand.getY()+NavigationGeometry.feetOffset((ServerLevel)owner.entity().level(),stand),
                        stand.getZ()+.5), .2),
                current.navigationPolicy);
    }

    private void clearTarget(GatherTaskImpl current) {
        runtime.releaseResources(current);
        runtime.releaseResourceDrops(owner.id());
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
            String unsafe = MiningSafety.excavationFailure((ServerLevel)owner.entity().level(), owner.entity(), current.target);
            if (unsafe != null) { current.lastFailureReason = unsafe; clearTarget(current); current.phase = GatherPhase.SEARCHING; return; }
            current.mining = owner.minerImpl().breakBlockForCollector(current.target);
            current.phase = GatherPhase.MINING;
        } else if (status == NavigationStatus.FAILED || status == NavigationStatus.CANCELLED
                || status == NavigationStatus.STUCK) {
            current.phase = GatherPhase.SEARCHING;
        }
    }

    private void tickMining(GatherTaskImpl current) {
        if (current.mining == null) { current.phase = GatherPhase.SEARCHING; return; }
        MiningStatus status = current.mining.status();
        if (status == MiningStatus.RUNNING) {
            String unsafe = MiningSafety.excavationFailure((ServerLevel)owner.entity().level(), owner.entity(), current.target);
            if (unsafe != null) { current.mining.cancel(); current.lastFailureReason = unsafe; clearTarget(current); current.phase = GatherPhase.SEARCHING; }
            return;
        }
        if (status == MiningStatus.SUCCESS) {
            current.brokenBlocks++;
            enqueueVeinNeighbours(current);
            current.collectingTicks = 0;
            current.navigation = null;
            current.phase = GatherPhase.COLLECTING;
        } else if (status == MiningStatus.FAILED || status == MiningStatus.CANCELLED) {
            var result = current.mining.completion().getNow(null);
            current.lastFailureReason = result == null
                    ? status.name().toLowerCase(java.util.Locale.ROOT)
                    : result.reason();
            current.phase = GatherPhase.SEARCHING;
        }
    }

    private void enqueueVeinNeighbours(GatherTaskImpl current) {
        ServerLevel level = (ServerLevel)owner.entity().level();
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            BlockPos neighbour = current.target.relative(direction);
            BlockPos origin = current.scanOrigin;
            int radius = current.policy.radius();
            if (origin == null || Math.abs(neighbour.getX() - origin.getX()) > radius
                    || Math.abs(neighbour.getY() - origin.getY()) > radius
                    || Math.abs(neighbour.getZ() - origin.getZ()) > radius || !MiningSafety.loaded(level, neighbour)) continue;
            if (current.request.blocks().contains(level.getBlockState(neighbour).getBlock()) && current.veinQueued.add(neighbour)) {
                current.veinCandidates.addLast(neighbour);
            }
        }
    }

    private void tickCollecting(GatherTaskImpl current) {
        current.collectingTicks++;
        collectNearbyDrops(current.request.items());
        current.collected = countItem(owner.entity(), current.request.items()) - current.baseline;
        if (current.collected >= current.request.count()) { current.complete("target_reached"); return; }
        ItemEntity drop = runtime.nearestResourceDrop(owner.id(), owner.entity(), current.request.items());
        if (drop != null && hasInventoryRoom(owner.entity(), current.request.items()) && current.collectingTicks < 200) {
            // Loot can bounce beyond the original mining approach. Follow the
            // actual item through the navigator rather than abandoning it after 20 ticks.
            if (current.navigation == null || current.navigation.completion().isDone()) {
                current.navigation = owner.navigatorImpl().goToAsChild(
                        new NavigationGoal.PositionGoal(drop.position(), 1.0), current.navigationPolicy);
            }
            return;
        }
        if (current.collectingTicks >= 20) {
            if (!hasInventoryRoom(owner.entity(), current.request.items())) current.fail("inventory_full");
            else {
                if (current.navigation != null) current.navigation.cancel();
                clearTarget(current);
                current.phase = GatherPhase.SEARCHING;
            }
        }
    }

    private void collectNearbyDrops(java.util.Set<net.minecraft.world.item.Item> items) {
        LivingEntity player = owner.entity();
        ServerLevel level = (ServerLevel) player.level();
        AABB area = player.getBoundingBox().inflate(2.5);
        for (net.minecraft.world.entity.Entity entity : level.getEntities(player, area, candidate ->
                candidate instanceof ItemEntity item && items.contains(item.getItem().getItem()))) {
            ((ItemEntity) entity).playerTouch(ActorSupport.context(player));
        }
    }

    private static int countItem(LivingEntity player, java.util.Set<net.minecraft.world.item.Item> items) {
        int count = 0;
        for (ItemStack stack : ActorSupport.context(player).getInventory().getNonEquipmentItems()) {
            if (items.contains(stack.getItem())) count += stack.getCount();
        }
        return count;
    }

    private static boolean hasInventoryRoom(LivingEntity player, java.util.Set<net.minecraft.world.item.Item> items) {
        for (net.minecraft.world.item.Item item : items) {
            ItemStack probe = new ItemStack(item);
            if (ActorSupport.context(player).getInventory().getSlotWithRemainingSpace(probe) >= 0
                    || ActorSupport.context(player).getInventory().getFreeSlot() >= 0) return true;
        }
        return false;
    }

    private static List<BlockPos> findStandingPositions(ServerLevel level, LivingEntity player, BlockPos target,
                                                        NavigationPolicy policy) {
        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos candidate : new BlockPos[] {
                target.west(), target.east(), target.north(), target.south(),
                target.west().above(), target.east().above(), target.north().above(), target.south().above() }) {
            if (!WorldSnapshot.loadedGeometryAt(level, candidate.getX(), candidate.getZ())) continue;
            // Do not stand on the block that will be removed. The post-break
            // support, rather than its current collision, determines safety.
            BlockState below = level.getBlockState(candidate.below());
            boolean supported = !below.getCollisionShape(level, candidate.below()).isEmpty()
                    || policy.allowWater() && !level.getFluidState(candidate.below()).isEmpty();
            if (!supported || NavigationSafety.dangerous(level, candidate)) continue;
            double offset = NavigationGeometry.feetOffset(level, candidate);
            var standing = player.getDimensions(net.minecraft.world.entity.Pose.STANDING);
            var crouching = player.getDimensions(net.minecraft.world.entity.Pose.CROUCHING);
            if (NavigationGeometry.bodyFits(level, candidate, offset, standing.width(), standing.height(), false)
                    || NavigationGeometry.bodyFits(level, candidate, offset, crouching.width(), crouching.height(), false)) {
                positions.add(candidate);
            }
        }
        return List.copyOf(positions);
    }

    private final class GatherTaskImpl implements GatherTask {
        private final GatherRequest request;
        private final GatherPolicy policy;
        private final NavigationPolicy navigationPolicy;
        private final CompletableFuture<GatherResult> completion = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private BlockPos scanOrigin;
        private final java.util.PriorityQueue<BlockPos> candidates = new java.util.PriorityQueue<>(
                Comparator.<BlockPos>comparingDouble(pos -> pos.distSqr(scanOrigin))
                        .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ));
        private int scanIndex;
        private final java.util.ArrayDeque<BlockPos> veinCandidates = new java.util.ArrayDeque<>();
        private final java.util.Set<BlockPos> veinQueued = new java.util.HashSet<>();
        private final ResourceScanCursor scanCursor;
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

        private GatherTaskImpl(GatherRequest request, GatherPolicy policy, NavigationPolicy navigationPolicy) {
            this.request = request;
            this.policy = policy;
            this.navigationPolicy = navigationPolicy;
            this.scanCursor = new ResourceScanCursor(policy.radius());
            this.baseline = countItem(owner.entity(), request.items());
        }
        @Override public GatherStatus status() { return status; }
        @Override public GatherPhase phase() { return phase; }
        @Override public int collectedItems() { return collected; }
        @Override public CompletableFuture<GatherResult> completion() { return completion.copy(); }
        @Override public void cancel() {
            runtime.ensureServerThread();
            if (cancelled.compareAndSet(false, true) && status == GatherStatus.RUNNING) {
                status = GatherStatus.CANCELLED;
                phase = GatherPhase.DONE;
                runtime.releaseResources(this);
                runtime.releaseResourceDrops(owner.id());
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
            runtime.releaseResourceDrops(owner.id());
            if (navigation != null) navigation.cancel();
            if (mining != null) mining.cancel();
            completion.complete(new GatherResult(status, reason, collected, brokenBlocks, System.nanoTime() - started));
        }
        private void fail(String reason) {
            if (status != GatherStatus.RUNNING) return;
            status = GatherStatus.FAILED;
            phase = GatherPhase.DONE;
            runtime.releaseResources(this);
            runtime.releaseResourceDrops(owner.id());
            if (navigation != null) navigation.cancel();
            if (mining != null) mining.cancel();
            completion.complete(new GatherResult(status, reason, collected, brokenBlocks, System.nanoTime() - started));
        }
    }
}
