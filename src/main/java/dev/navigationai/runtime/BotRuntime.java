package dev.navigationai.runtime;

import com.mojang.authlib.GameProfile;
import dev.navigationai.NavigationAiMod;
import dev.navigationai.api.BotCreateOptions;
import dev.navigationai.api.BotHandle;
import dev.navigationai.api.BotService;
import dev.navigationai.api.NavigationMetrics;
import dev.navigationai.api.NavigationValidationMetrics;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns all bots for one running server. All mutations happen on the server thread. */
public final class BotRuntime implements BotService, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("navigation_ai/runtime");
    private static final int SNAPSHOT_BUDGET_PER_TICK = 4;
    private static final int SNAPSHOT_CELLS_BUDGET_PER_TICK = 4_096;
    private static final int PATH_NODES_BUDGET_PER_TICK = 40_000;
    private final MinecraftServer server;
    private final Map<UUID, BotHandleImpl> bots = new ConcurrentHashMap<>();
    private final Map<UUID, BotConnection> connections = new ConcurrentHashMap<>();
    private final Set<UUID> transferringVillagers = new HashSet<>();
    private final Map<UUID, VillagerTicket> villagerTickets = new java.util.HashMap<>();
    private final Map<VillagerTicket, Integer> villagerTicketRefs = new java.util.HashMap<>();
    private final Map<UUID, Integer> villagerTicketGrace = new java.util.HashMap<>();
    private final List<PendingNavigation> pendingNavigations = new ArrayList<>();
    private final List<PendingActivity> pendingActivities = new ArrayList<>();
    private final PathSearchScheduler pathScheduler;
    private final PathNodeBudget pathNodeBudget = new PathNodeBudget(PATH_NODES_BUDGET_PER_TICK);
    private final NavigationMetricsCollector metrics = new NavigationMetricsCollector();
    private int restoreDelayTicks = 20;
    private int snapshotBudgetTick = Integer.MIN_VALUE;
    private int snapshotsThisTick;
    private int snapshotCellsTick = Integer.MIN_VALUE;
    private int snapshotCellsThisTick;
    private volatile int snapshotBudgetPerTick = SNAPSHOT_BUDGET_PER_TICK;
    private volatile int adaptivePathNodeBudget = PATH_NODES_BUDGET_PER_TICK;
    private volatile int chunkGenerationBudgetPerTick = 4;
    private int generationBudgetTick = Integer.MIN_VALUE;
    private int generatedChunksThisTick;
    private final Map<UUID, java.util.Set<ChunkTicket>> navigationTickets = new ConcurrentHashMap<>();
    private final Map<ChunkTicket, Integer> navigationTicketRefs = new ConcurrentHashMap<>();
    private final Map<UUID, NavigationTicketDemand> navigationTicketDemands = new ConcurrentHashMap<>();
    private static final int MAX_PENDING_CHUNK_REQUESTS = 32;
    private final Set<ChunkTicket> pendingTicketLoads = new HashSet<>();
    private int nextTicketBotIndex;
    private long transitionValidationCalls, transitionValidationNanos, transitionValidationDeferrals;
    /** A failed load must never be followed by a write of partial state. */
    private boolean persistenceLoadFailed;
    private volatile boolean closed;
    private final Set<java.util.concurrent.CompletableFuture<?>> pendingOperations = ConcurrentHashMap.newKeySet();
    private int nextBotIndex;
    private int planningBudgetTick = Integer.MIN_VALUE;
    private long planningDeadlineNanos;
    private long planningTimeBudgetNanos = 5_000_000L;
    private int resourceScanTick = Integer.MIN_VALUE;
    private int resourceScanCells;
    private final Map<ResourceTarget, Object> resourceReservations = new java.util.HashMap<>();
    private final Map<UUID, List<net.minecraft.world.entity.item.ItemEntity>> resourceDrops = new java.util.HashMap<>();

    public BotRuntime(MinecraftServer server) {
        this.server = java.util.Objects.requireNonNull(server);
        ensureServerThread();
        int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        this.pathScheduler = new PathSearchScheduler(workers, 64);
        this.pathNodeBudget.beginTick(server.getTickCount());
        loadPersisted();
    }

    public MinecraftServer server() { return server; }
    public BotService service() { return this; }
    public NavigationMetrics metrics() { return metrics.snapshot(pathScheduler.queuedTasks(), pathScheduler.activeTasks()); }
    public NavigationValidationMetrics transitionValidationMetrics() {
        ensureServerThread();
        return new NavigationValidationMetrics(transitionValidationCalls, transitionValidationNanos, transitionValidationDeferrals);
    }
    void recordTransitionValidation(long nanos, boolean deferred) {
        transitionValidationCalls++; transitionValidationNanos+=nanos;
        if (deferred) transitionValidationDeferrals++;
    }
    PathSearchScheduler pathScheduler() { return pathScheduler; }
    PathNodeBudget pathNodeBudget() { return pathNodeBudget; }

    void recordSnapshot(long durationNanos) { metrics.recordSnapshot(durationNanos); }
    void recordPathSearchSubmitted() { metrics.recordPathSearchSubmitted(); }
    void recordPathSearchCancelled() { metrics.recordPathSearchCancelled(); }
    void recordPathSearchRejected() { metrics.recordPathSearchRejected(); }
    void recordBackpressureDeferral() { metrics.recordBackpressureDeferral(); }
    void recordNodeBudgetDeferral() { metrics.recordNodeBudgetDeferral(); }
    void recordPathSearch(long nodesVisited, long durationNanos) { metrics.recordPathSearch(nodesVisited, durationNanos); }
    void recordReplan() { metrics.recordReplan(); }

    boolean shouldDeferPathSearch(int priority) { return pathScheduler.shouldDefer(priority); }

    boolean tryAcquireSnapshotBudget() {
        if (System.nanoTime() >= planningDeadline()) return false;
        int tick = server.getTickCount();
        if (tick != snapshotBudgetTick) {
            snapshotBudgetTick = tick;
            snapshotsThisTick = 0;
        }
        if (snapshotsThisTick >= snapshotBudgetPerTick) return false;
        snapshotsThisTick++;
        return true;
    }

    int acquireSnapshotCells(int requested) {
        if (System.nanoTime() >= planningDeadline()) return 0;
        int tick = server.getTickCount();
        if (tick != snapshotCellsTick) {
            snapshotCellsTick = tick;
            snapshotCellsThisTick = 0;
        }
        int granted = Math.min(requested, SNAPSHOT_CELLS_BUDGET_PER_TICK - snapshotCellsThisTick);
        snapshotCellsThisTick += granted;
        return granted;
    }

    long planningDeadline() {
        int tick = server.getTickCount();
        if (tick != planningBudgetTick) {
            planningBudgetTick = tick;
            planningDeadlineNanos = System.nanoTime() + planningTimeBudgetNanos;
        }
        return planningDeadlineNanos;
    }

    int acquireResourceScanCells(int requested) {
        if (System.nanoTime() >= planningDeadline()) return 0;
        int tick = server.getTickCount();
        if (tick != resourceScanTick) { resourceScanTick = tick; resourceScanCells = 0; }
        int granted = Math.min(requested, 1_024 - resourceScanCells);
        resourceScanCells += granted;
        return granted;
    }

    boolean reserveResource(ServerLevel level, BlockPos pos, Object owner) {
        ResourceTarget target = new ResourceTarget(level.dimension(), pos.immutable());
        Object previous = resourceReservations.putIfAbsent(target, owner);
        return previous == null || previous == owner;
    }

    void releaseResources(Object owner) { resourceReservations.values().removeIf(value -> value == owner); }

    void reserveDrop(UUID botId, net.minecraft.world.entity.item.ItemEntity item) {
        item.setTarget(botId);
        resourceDrops.computeIfAbsent(botId, ignored -> new ArrayList<>()).add(item);
    }

    void releaseResourceDrops(UUID botId) {
        var items = resourceDrops.remove(botId);
        if (items != null) for (var item : items) if (!item.isRemoved()) item.setTarget(null);
    }

    net.minecraft.world.entity.item.ItemEntity nearestResourceDrop(UUID botId, net.minecraft.world.entity.LivingEntity player,
                                                                  java.util.Set<net.minecraft.world.item.Item> items) {
        return resourceDrops.getOrDefault(botId, List.of()).stream()
                .filter(item -> !item.isRemoved() && item.level() == player.level() && items.contains(item.getItem().getItem()))
                .min(java.util.Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }

    boolean tryAcquireChunkGeneration() {
        int tick = server.getTickCount();
        if (tick != generationBudgetTick) {
            generationBudgetTick = tick;
            generatedChunksThisTick = 0;
        }
        if (generatedChunksThisTick >= chunkGenerationBudgetPerTick) return false;
        generatedChunksThisTick++;
        return true;
    }

    /** Replaces a bot's retained chunk area with the area around its current plan. */
    void updateNavigationTickets(UUID botId, ServerLevel level, dev.navigationai.navigation.GridPos start, int radius) {
        if (radius < 1) {
            releaseNavigationTickets(botId);
            return;
        }
        int chunkRadius = Math.max(1, (radius + 15) / 16);
        Set<ChunkTicket> desired = new HashSet<>();
        ChunkPos center = new ChunkPos(start.x() >> 4, start.z() >> 4);
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                desired.add(new ChunkTicket(level.dimension(),
                        new ChunkPos(center.x() + dx, center.z() + dz).pack()));
            }
        }

        Set<ChunkTicket> tickets = navigationTickets.computeIfAbsent(botId,
                ignored -> ConcurrentHashMap.newKeySet());
        for (ChunkTicket ticket : new HashSet<>(tickets)) {
            if (!desired.contains(ticket) && tickets.remove(ticket)) {
                releaseNavigationTicket(ticket);
            }
        }
        navigationTicketDemands.put(botId, new NavigationTicketDemand(center, desired));
        serviceNavigationTicketDemands();
        if (tickets.isEmpty()) navigationTickets.remove(botId, tickets);
    }

    private void serviceNavigationTicketDemands() {
        pendingTicketLoads.removeIf(ticket -> {
            ServerLevel level = server.getLevel(ticket.dimension());
            ChunkPos pos = ChunkPos.unpack(ticket.chunk());
            return level == null || level.getChunkSource().getChunkNow(pos.x(), pos.z()) != null;
        });
        if (navigationTicketDemands.isEmpty()) return;
        List<UUID> waiting = new ArrayList<>(navigationTicketDemands.keySet());
        waiting.sort(UUID::compareTo);
        int first = Math.floorMod(nextTicketBotIndex++, waiting.size());
        for (int index = 0; index < waiting.size(); index++) {
            UUID botId = waiting.get((first + index) % waiting.size());
            NavigationTicketDemand demand = navigationTicketDemands.get(botId);
            if (demand == null) continue;
            Set<ChunkTicket> retained = navigationTickets.computeIfAbsent(botId,
                    ignored -> ConcurrentHashMap.newKeySet());
            ChunkTicket next = demand.tickets().stream().filter(ticket -> !retained.contains(ticket))
                    .min(java.util.Comparator.comparingInt(ticket -> {
                        ChunkPos pos = ChunkPos.unpack(ticket.chunk());
                        return Math.abs(pos.x() - demand.center().x()) + Math.abs(pos.z() - demand.center().z());
                    })).orElse(null);
            if (next == null) {
                navigationTicketDemands.remove(botId, demand);
                continue;
            }
            if (!navigationTicketRefs.containsKey(next)) {
                ServerLevel level = server.getLevel(next.dimension());
                ChunkPos pos = ChunkPos.unpack(next.chunk());
                if (level != null && level.getChunkSource().getChunkNow(pos.x(), pos.z()) == null
                        && pendingTicketLoads.size() >= MAX_PENDING_CHUNK_REQUESTS) continue;
                if (!tryAcquireChunkGeneration()) continue;
            }
            if (retained.add(next)) retainNavigationTicket(next);
        }
    }

    boolean navigationChunksPending(UUID id) {
        if(navigationTicketDemands.containsKey(id))return true;
        var retained=navigationTickets.get(id);
        return retained!=null&&retained.stream().anyMatch(pendingTicketLoads::contains);
    }

    private void retainNavigationTicket(ChunkTicket ticket) {
        int references = navigationTicketRefs.merge(ticket, 1, Integer::sum);
        if (references == 1) {
            ServerLevel level = server.getLevel(ticket.dimension());
            if (level != null) {
                ChunkPos pos = ChunkPos.unpack(ticket.chunk());
                if (level.getChunkSource().getChunkNow(pos.x(), pos.z()) == null) pendingTicketLoads.add(ticket);
                level.getChunkSource().addTicketWithRadius(NavigationAiMod.NAVIGATION_TICKET,
                        pos, 2);
            }
        }
    }

    void releaseNavigationTickets(UUID botId) {
        navigationTicketDemands.remove(botId);
        Set<ChunkTicket> tickets = navigationTickets.remove(botId);
        if (tickets == null) return;
        for (ChunkTicket ticket : tickets) {
            releaseNavigationTicket(ticket);
        }
    }

    private void releaseNavigationTicket(ChunkTicket ticket) {
        Integer references = navigationTicketRefs.compute(ticket, (ignored, count) -> {
            if (count == null || count <= 1) return null;
            return count - 1;
        });
        if (references == null) {
            pendingTicketLoads.remove(ticket);
            ServerLevel level = server.getLevel(ticket.dimension());
            if (level != null) {
                level.getChunkSource().removeTicketWithRadius(NavigationAiMod.NAVIGATION_TICKET,
                        ChunkPos.unpack(ticket.chunk()), 2);
            }
        }
    }

    @Override
    public BotHandle create(BotCreateOptions options) {
        ensureServerThread();
        ensureOpen();
        java.util.Objects.requireNonNull(options);
        if (bots.containsKey(options.uuid())) throw new IllegalArgumentException("A bot with this UUID already exists");
        GameProfile profile = new GameProfile(options.uuid(), options.name());
        ServerPlayer player = new ServerPlayer(server, options.level(), profile, ClientInformation.createDefault());
        player.setPos(options.position());
        player.setYRot(options.yaw());
        player.setXRot(options.pitch());
        player.setYHeadRot(options.yaw());

        // Let PlayerList perform the same registration sequence as a real
        // player. The previous implementation manually inserted the player
        // into PlayerList and ServerLevel, which was enough for the server
        // and locator bar but could leave the entity pairing incomplete for
        // clients that were already connected. A connection that silently
        // drops packets lets us reuse the vanilla sequence without creating
        // a real network client for the fake player.
        BotConnection connection = new BotConnection(player);
        server.getPlayerList().placeNewPlayer(
            connection,
            player,
            CommonListenerCookie.createInitial(profile, false)
        );

        BotHandleImpl handle = new BotHandleImpl(this, player, options.name());
        bots.put(player.getUUID(), handle);
        connections.put(player.getUUID(), connection);
        LOGGER.info("Created navigation bot {} ({})", options.name(), player.getUUID());
        return handle;
    }

    @Override public Optional<BotHandle> find(UUID id) { return Optional.ofNullable(bots.get(id)).map(b -> b); }
    @Override public BotHandle createVillager(BotCreateOptions options) {
        ensureServerThread(); ensureOpen(); java.util.Objects.requireNonNull(options);
        if (options.level().getServer() != server) throw new IllegalArgumentException("Wrong server");
        if (bots.containsKey(options.uuid()) || options.level().getEntity(options.uuid()) != null) {
            throw new IllegalArgumentException("An entity with this UUID already exists");
        }
        var villager = new net.minecraft.world.entity.npc.villager.Villager(
                net.minecraft.world.entity.EntityTypes.VILLAGER, options.level());
        villager.setUUID(options.uuid()); villager.setPos(options.position());
        villager.setYRot(options.yaw()); villager.setXRot(options.pitch()); villager.setYHeadRot(options.yaw());
        villager.setCustomName(net.minecraft.network.chat.Component.literal(options.name()));
        villager.setPersistenceRequired();
        if (!options.level().addFreshEntity(villager)) throw new IllegalStateException("Villager spawn rejected");
        try { return attach(villager, options.name()); }
        catch (RuntimeException failure) { villager.discard(); throw failure; }
    }

    @Override public BotHandle attach(net.minecraft.world.entity.npc.villager.Villager villager) {
        java.util.Objects.requireNonNull(villager);
        return attach(villager, villager.getName().getString());
    }

    private BotHandleImpl attach(net.minecraft.world.entity.npc.villager.Villager villager, String name) {
        ensureServerThread(); ensureOpen(); java.util.Objects.requireNonNull(villager);
        if (villager.level().getServer() != server || villager.level().isClientSide()
                || !villager.isAlive() || villager.isRemoved()
                || ((ServerLevel) villager.level()).getChunkSource().getChunkNow(
                        villager.blockPosition().getX() >> 4, villager.blockPosition().getZ() >> 4) == null) {
            throw new IllegalArgumentException("Expected a live, loaded villager on this server");
        }
        BotHandleImpl existing = bots.get(villager.getUUID());
        if (existing != null) {
            if (existing.entity() != villager) throw new IllegalArgumentException("Duplicate controlled UUID");
            return existing;
        }
        var access = (VillagerAccess) villager;
        if (access.navigationAiController() != null) throw new IllegalStateException("Villager already has a controller");
        var controller = new VillagerController(this, villager, name);
        BotHandleImpl handle = controller.handle();
        bots.put(handle.id(), handle);
        villagerTicketGrace.put(handle.id(), server.getTickCount() + 40);
        updateVillagerTicket(handle);
        CompoundTag saved = access.navigationAiSavedState();
        try {
            if (saved != null) controller.restore(saved);
            access.navigationAiSavedState(null);
        } catch (RuntimeException failure) {
            bots.remove(handle.id(), handle);
            villagerTicketGrace.remove(handle.id());
            releaseVillagerTicket(handle.id());
            pendingNavigations.removeIf(p -> p.handle() == handle);
            pendingActivities.removeIf(p -> p.handle() == handle);
            access.navigationAiController(null);
            throw failure;
        }
        return handle;
    }

    public void entityLoaded(net.minecraft.world.entity.Entity entity) {
        if (closed || transferringVillagers.contains(entity.getUUID())
                || !(entity instanceof net.minecraft.world.entity.npc.villager.Villager villager)) return;
        CompoundTag saved = ((VillagerAccess) villager).navigationAiSavedState();
        if (saved != null) {
            try { attach(villager, saved.getStringOr("name", villager.getName().getString())); }
            catch (RuntimeException failure) { LOGGER.error("Could not restore villager control {}", villager.getUUID(), failure); }
        }
    }

    public void entityUnloaded(net.minecraft.world.entity.Entity entity) {
        if (closed || transferringVillagers.contains(entity.getUUID())
                || !(entity instanceof net.minecraft.world.entity.npc.villager.Villager villager)) return;
        // Fabric also calls ENTITY_UNLOAD on temporary tracking visibility changes.
        // Only actual removal ends the attachment; pending simulation tickets can restore tracking.
        if (entity.getRemovalReason() == null) return;
        BotHandleImpl handle = bots.get(villager.getUUID());
        if (handle == null || handle.entity() != villager) return;
        LOGGER.debug("Unloading controlled villager {} reason={} state={} navigation={} active={}",
                handle.name(), villager.getRemovalReason(), handle.state(), handle.navigator().status(), handle.controlsBody());
        var access = (VillagerAccess) villager;
        if (villager.getHealth() > 0 && villager.getRemovalReason().shouldSave()) {
            access.navigationAiSavedState(access.navigationAiController().snapshotState());
        }
        handle.prepareForShutdown();
        handle.retireForUnload();
        detachVillager(handle, true);
    }

    void restoreVillagerTasks(BotHandleImpl handle, ValueInput input) {
        input.child("navigation").ifPresent(nav -> pendingNavigations.add(new PendingNavigation(handle, nav, handle.control().generation())));
        input.child("activity").ifPresent(activity -> pendingActivities.add(new PendingActivity(handle, activity, handle.control().generation())));
    }

    void detachVillager(BotHandleImpl handle, boolean unloading) {
        bots.remove(handle.id(), handle);
        villagerTicketGrace.remove(handle.id());
        releaseResourceDrops(handle.id()); releaseNavigationTickets(handle.id()); releaseVillagerTicket(handle.id());
        pendingNavigations.removeIf(p -> p.handle() == handle);
        pendingActivities.removeIf(p -> p.handle() == handle);
        var access = (VillagerAccess) handle.entity();
        if (access.navigationAiController() != null) access.navigationAiController().release(unloading);
        handle.markDetached();
    }

    boolean teleportBot(BotHandleImpl handle, ServerLevel target, Vec3 position) {
        var entity = handle.entity();
        if (handle.kind() == dev.navigationai.api.BotKind.PLAYER) {
            return entity.teleportTo(target, position.x, position.y, position.z, Set.of(), entity.getYRot(), entity.getXRot(), false);
        }
        var controller = ((VillagerAccess) entity).navigationAiController();
        transferringVillagers.add(handle.id());
        try {
            var moved = entity.teleport(new net.minecraft.world.level.portal.TeleportTransition(target, position,
                    Vec3.ZERO, entity.getYRot(), entity.getXRot(), net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
            if (!(moved instanceof net.minecraft.world.entity.npc.villager.Villager villager)) return false;
            controller.rebind(villager);
            updateVillagerTicket(handle);
            return true;
        } finally { transferringVillagers.remove(handle.id()); }
    }

    private void updateVillagerTicket(BotHandleImpl handle) {
        if (handle.kind() != dev.navigationai.api.BotKind.VILLAGER) return;
        if (handle.state() == dev.navigationai.api.BotState.ACTIVE && handle.controlsBody()) {
            villagerTicketGrace.put(handle.id(), server.getTickCount() + 40);
        }
        if (handle.state() != dev.navigationai.api.BotState.ACTIVE || !handle.controlsBody()
                && server.getTickCount() > villagerTicketGrace.getOrDefault(handle.id(), -1)) {
            releaseVillagerTicket(handle.id()); return;
        }
        var body = handle.entity();
        VillagerTicket next = new VillagerTicket(body.level().dimension(), new ChunkPos(body.blockPosition().getX() >> 4,
                body.blockPosition().getZ() >> 4).pack(), handle.combatImpl().simulationRadius());
        if (next.equals(villagerTickets.get(handle.id()))) return;
        if (villagerTicketRefs.merge(next, 1, Integer::sum) == 1) {
            ((ServerLevel) body.level()).getChunkSource().addTicketWithRadius(NavigationAiMod.VILLAGER_TICKET,
                    ChunkPos.unpack(next.chunk()), next.radius());
        }
        releaseVillagerTicket(handle.id());
        villagerTickets.put(handle.id(), next);
    }

    private void releaseVillagerTicket(UUID id) {
        VillagerTicket ticket = villagerTickets.remove(id);
        if (ticket == null) return;
        Integer left = villagerTicketRefs.compute(ticket, (key, value) -> value == null || value <= 1 ? null : value - 1);
        if (left == null) {
            ServerLevel level = server.getLevel(ticket.dimension());
            if (level != null) level.getChunkSource().removeTicketWithRadius(NavigationAiMod.VILLAGER_TICKET,
                    ChunkPos.unpack(ticket.chunk()), ticket.radius());
        }
    }
    @Override public Optional<BotHandle> find(String name) { return bots.values().stream().filter(b -> b.name().equalsIgnoreCase(name)).map(b -> (BotHandle)b).findFirst(); }
    @Override public Collection<BotHandle> bots() { return java.util.List.copyOf(bots.values()); }

    @Override public <T> java.util.concurrent.CompletableFuture<T> submit(java.util.function.Function<BotService, T> operation) {
        java.util.Objects.requireNonNull(operation);
        var future = new java.util.concurrent.CompletableFuture<T>();
        pendingOperations.add(future);
        future.whenComplete((result, failure) -> pendingOperations.remove(future));
        if (closed) {
            future.completeExceptionally(new IllegalStateException("Bot runtime is closed"));
            return future;
        }
        // schedule() always queues, including calls made from the server thread.
        server.schedule(new net.minecraft.server.TickTask(server.getTickCount(), () -> {
            if (future.isDone()) return;
            try { ensureOpen(); future.complete(operation.apply(this)); }
            catch (Throwable failure) { future.completeExceptionally(failure); }
        }));
        return future;
    }

    void remove(BotHandleImpl handle) {
        ensureServerThread();
        pendingNavigations.removeIf(restore -> {
            if (restore.handle() != handle) return false;
            handle.navigatorImpl().restoreDiscardedState(restore.input());
            return true;
        });
        pendingActivities.removeIf(restore -> restore.handle() == handle);
        if (bots.remove(handle.id(), handle)) {
            releaseResourceDrops(handle.id());
            releaseNavigationTickets(handle.id());
            if (handle.kind() == dev.navigationai.api.BotKind.VILLAGER) {
                releaseVillagerTicket(handle.id());
                var access = (VillagerAccess) handle.entity();
                if (access.navigationAiController() != null) access.navigationAiController().release(true);
                access.navigationAiSavedState(null);
                handle.entity().discard();
                return;
            }
            ServerPlayer player = handle.player();
            connections.remove(player.getUUID());
            if (!player.isRemoved()) server.getPlayerList().remove(player);
        }
    }

    public void tick() {
        ensureServerThread();
        if (closed) return;
        long started = System.nanoTime();
        try {
            updateAdaptiveBudgets();
            resourceDrops.values().forEach(items -> items.removeIf(net.minecraft.world.entity.Entity::isRemoved));
            resourceDrops.values().removeIf(List::isEmpty);
            pathNodeBudget.beginTick(server.getTickCount(), adaptivePathNodeBudget);
            serviceNavigationTicketDemands();
            if (restoreDelayTicks > 0) {
                restoreDelayTicks--;
            } else {
                restorePendingNavigations();
            }
            List<BotHandleImpl> active = new ArrayList<>(bots.values());
            active.sort(java.util.Comparator.comparing(BotHandleImpl::id));
            if (!active.isEmpty()) {
                int first = Math.floorMod(nextBotIndex++, active.size());
                for (int index = 0; index < active.size(); index++) {
                    BotHandleImpl handle = active.get((first + index) % active.size());
                    updateVillagerTicket(handle);
                    handle.tick();
                    updateVillagerTicket(handle);
                }
            }
        } finally {
            metrics.recordServerTick(System.nanoTime() - started);
        }
    }

    /** Scales planning work with the number of active fake players and recent tick cost. */
    private void updateAdaptiveBudgets() {
        int count = bots.size();
        int snapshotLimit;
        int nodeLimit;
        if (count <= 1) {
            snapshotLimit = 4;
            nodeLimit = PATH_NODES_BUDGET_PER_TICK;
        } else if (count <= 10) {
            snapshotLimit = 4;
            nodeLimit = 32_000;
        } else if (count <= 25) {
            snapshotLimit = 3;
            nodeLimit = 24_000;
        } else {
            snapshotLimit = 2;
            nodeLimit = 16_000;
        }
        chunkGenerationBudgetPerTick = count <= 1 ? 4 : count <= 10 ? 4 : count <= 25 ? 3 : 2;
        // A slow recent tick takes precedence over the static bot-count tier.
        long lastTick = Math.max(server.getAverageTickTimeNanos(),
                metrics.snapshot(pathScheduler.queuedTasks(), pathScheduler.activeTasks()).lastTickNanos());
        long target=server.tickRateManager().nanosecondsPerTick();
        planningTimeBudgetNanos = dev.navigationai.api.TickWorkBudget.planningNanos(target,lastTick);
        if (dev.navigationai.api.TickWorkBudget.overloaded(target,lastTick)) {
            snapshotLimit = Math.max(1, snapshotLimit - 1);
            nodeLimit = Math.max(8_000, nodeLimit * 3 / 4);
        }
        snapshotBudgetPerTick = snapshotLimit;
        adaptivePathNodeBudget = nodeLimit;
    }

    void applyPendingMotion(ServerPlayer player) {
        BotConnection connection = connections.get(player.getUUID());
        if (connection == null) return;
        Vec3 motion = connection.takePendingMotion();
        if (motion != null) player.setDeltaMovement(motion);
    }

    void ensureServerThread() { if (!server.isSameThread()) throw new IllegalStateException("Bot API must be called on the server thread"); }
    void ensureOpen() { if (closed) throw new IllegalStateException("Bot runtime is closed"); }

    @Override public void close() {
        ensureServerThread();
        if (closed) return;
        restorePendingNavigations();
        closed = true;
        for (var operation : pendingOperations) operation.completeExceptionally(new IllegalStateException("Bot runtime is closed"));
        BotHandleImpl[] handles = bots.values().toArray(BotHandleImpl[]::new);
        for (BotHandleImpl handle : handles) {
            if (handle.state() != dev.navigationai.api.BotState.REMOVED) handle.prepareForShutdown();
        }
        savePersisted();
        for (BotHandleImpl handle : handles) {
            if (handle.kind() == dev.navigationai.api.BotKind.VILLAGER) {
                var access = (VillagerAccess) handle.entity();
                if (access.navigationAiController() != null) {
                    access.navigationAiSavedState(access.navigationAiController().snapshotState());
                    detachVillager(handle, true);
                }
            } else if (handle.state() != dev.navigationai.api.BotState.REMOVED) handle.removeForShutdown();
        }
        for (UUID id : new ArrayList<>(navigationTickets.keySet())) releaseNavigationTickets(id);
        pathScheduler.close();
    }

    private Path persistencePath() { return server.getWorldPath(LevelResource.ROOT).resolve("navigation_ai_bots.dat"); }

    private void savePersisted() {
        if (persistenceLoadFailed) {
            LOGGER.warn("Skipping navigation bot save because the previous save could not be loaded; "
                    + "the original file was kept for recovery");
            return;
        }
        Path target = persistencePath();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            TagValueOutput root = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
            root.putInt("version", 3);
            ValueOutput.ValueOutputList entries = root.childrenList("bots");
            for (BotHandleImpl handle : bots.values()) {
                if (handle.kind() == dev.navigationai.api.BotKind.VILLAGER) continue;
                ValueOutput entry = entries.addChild();
                ServerPlayer player = handle.player();
                entry.putString("name", handle.name());
                entry.putString("dimension", player.level().dimension().identifier().toString());
                player.saveWithoutId(entry);
                handle.navigatorImpl().saveState(entry.child("navigation"));
                handle.activitiesImpl().saveState(entry.child("activity"));
            }
            NbtIo.writeCompressed(root.buildResult(), temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            LOGGER.warn("Could not save navigation bots", exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (Exception cleanupFailure) {
                LOGGER.warn("Could not clean temporary navigation bot save {}", temporary, cleanupFailure);
            }
        }
    }

    private void loadPersisted() {
        Path path = persistencePath();
        if (!Files.exists(path)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), root);
            input.childrenListOrEmpty("bots").stream().forEach(entry -> {
                try {
                    restoreEntry(entry, pendingNavigations);
                } catch (RuntimeException invalidEntry) {
                    // Keep loading independent entries so one corrupt bot does
                    // not prevent healthy bots from being restored.  Mark the
                    // whole source as recovery-only so close() cannot overwrite
                    // the evidence needed to recover the bad entry.
                    persistenceLoadFailed = true;
                    LOGGER.warn("Skipping invalid persisted navigation bot entry", invalidEntry);
                }
            });
        } catch (Exception exception) {
            persistenceLoadFailed = true;
            LOGGER.warn("Could not load navigation bots; keeping the save for manual recovery", exception);
        }
    }

    private void restorePendingNavigations() {
        if (pendingNavigations.isEmpty() && pendingActivities.isEmpty()) return;
        Map<BotHandleImpl, Long> eligible = new java.util.HashMap<>();
        for (PendingActivity restore : pendingActivities) {
            if (restore.handle().state() == dev.navigationai.api.BotState.ACTIVE
                    && restore.handle().control().current(restore.generation())) eligible.put(restore.handle(), restore.generation());
        }
        PendingNavigation[] pending = pendingNavigations.toArray(PendingNavigation[]::new);
        pendingNavigations.clear();
        for (PendingNavigation restore : pending) {
            try {
                if (restore.handle().state() != dev.navigationai.api.BotState.ACTIVE) continue;
                if (!restore.handle().control().current(restore.generation())) {
                    restore.handle().navigatorImpl().restoreDiscardedState(restore.input());
                    continue;
                }
                if (!restore.handle().navigatorImpl().restoreState(restore.input())) {
                    LOGGER.warn("Skipping persisted navigation for bot {}: follow target is unavailable",
                            restore.handle().name());
                }
                if (eligible.containsKey(restore.handle())) eligible.put(restore.handle(), restore.handle().control().generation());
            } catch (RuntimeException exception) {
                LOGGER.warn("Skipping invalid persisted navigation for bot {}", restore.handle().name(), exception);
            }
        }
        PendingActivity[] activities = pendingActivities.toArray(PendingActivity[]::new);
        pendingActivities.clear();
        for (PendingActivity restore : activities) {
            try {
                Long expected = eligible.get(restore.handle());
                if (expected == null || !restore.handle().control().current(expected)) continue;
                restore.handle().activitiesImpl().restoreState(restore.input());
            } catch (RuntimeException invalid) {
                persistenceLoadFailed = true;
                LOGGER.warn("Skipping invalid persisted activity for bot {}", restore.handle().name(), invalid);
            }
        }
    }

    private void restoreEntry(ValueInput entry, List<PendingNavigation> pending) {
        String name = entry.getStringOr("name", "bot");
        String dimensionId = entry.getStringOr("dimension", Level.OVERWORLD.identifier().toString());
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimensionId));
        var level = server.getLevel(key);
        if (level == null) {
            persistenceLoadFailed = true;
            LOGGER.warn("Skipping bot {}: dimension {} is unavailable", name, dimensionId);
            return;
        }
        UUID uuid = entry.read("UUID", net.minecraft.core.UUIDUtil.CODEC).orElse(UUID.randomUUID());
        Vec3 position = entry.read("Pos", Vec3.CODEC).orElse(Vec3.ZERO);
        Vec2 rotation = entry.read("Rotation", Vec2.CODEC).orElse(Vec2.ZERO);
        BotHandleImpl handle = (BotHandleImpl) create(new BotCreateOptions(name, uuid, level, position, rotation.x, rotation.y));
        handle.player().load(entry);
        entry.child("navigation").ifPresent(navigation -> pending.add(new PendingNavigation(handle, navigation, handle.control().generation())));
        entry.child("activity").ifPresent(activity -> pendingActivities.add(new PendingActivity(handle, activity, handle.control().generation())));
    }

    private record PendingNavigation(BotHandleImpl handle, ValueInput input, long generation) { }
    private record PendingActivity(BotHandleImpl handle, ValueInput input, long generation) { }

    private record ChunkTicket(ResourceKey<Level> dimension, long chunk) { }
    private record VillagerTicket(ResourceKey<Level> dimension, long chunk, int radius) { }
    private record NavigationTicketDemand(ChunkPos center, Set<ChunkTicket> tickets) { }
    private record ResourceTarget(ResourceKey<Level> dimension, BlockPos position) { }

    /** A disconnected connection is sufficient for server simulation and never queues packets. */
    private static final class BotConnection extends Connection {
        private final ServerPlayer player;
        private volatile Vec3 pendingMotion;

        private BotConnection(ServerPlayer player) {
            super(PacketFlow.SERVERBOUND);
            this.player = player;
        }

        private void capture(Packet<?> packet) {
            if (packet instanceof ClientboundSetEntityMotionPacket motion && motion.id() == player.getId()) {
                pendingMotion = motion.movement();
            }
        }

        private Vec3 takePendingMotion() {
            Vec3 motion = pendingMotion;
            pendingMotion = null;
            return motion;
        }

        @Override public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) { }
        @Override public void send(Packet<?> packet) { capture(packet); }
        @Override public void send(Packet<?> packet, io.netty.channel.ChannelFutureListener listener) { capture(packet); }
        @Override public void send(Packet<?> packet, io.netty.channel.ChannelFutureListener listener, boolean flush) { capture(packet); }
    }
}
