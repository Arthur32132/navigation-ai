package dev.navigationai.runtime;

import com.mojang.authlib.GameProfile;
import dev.navigationai.api.BotCreateOptions;
import dev.navigationai.api.BotHandle;
import dev.navigationai.api.BotService;
import dev.navigationai.api.NavigationMetrics;
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
    private static final int PATH_NODES_BUDGET_PER_TICK = 40_000;
    private final MinecraftServer server;
    private final Map<UUID, BotHandleImpl> bots = new ConcurrentHashMap<>();
    private final Map<UUID, BotConnection> connections = new ConcurrentHashMap<>();
    private final List<PendingNavigation> pendingNavigations = new ArrayList<>();
    private final PathSearchScheduler pathScheduler;
    private final PathNodeBudget pathNodeBudget = new PathNodeBudget(PATH_NODES_BUDGET_PER_TICK);
    private final NavigationMetricsCollector metrics = new NavigationMetricsCollector();
    private int restoreDelayTicks = 20;
    private int snapshotBudgetTick = Integer.MIN_VALUE;
    private int snapshotsThisTick;
    private volatile int snapshotBudgetPerTick = SNAPSHOT_BUDGET_PER_TICK;
    private volatile int adaptivePathNodeBudget = PATH_NODES_BUDGET_PER_TICK;
    private volatile int chunkGenerationBudgetPerTick = 4;
    private int generationBudgetTick = Integer.MIN_VALUE;
    private int generatedChunksThisTick;
    private final Map<UUID, java.util.Set<ChunkTicket>> navigationTickets = new ConcurrentHashMap<>();
    private final Map<ChunkTicket, Integer> navigationTicketRefs = new ConcurrentHashMap<>();
    /** A failed load must never be followed by a write of partial state. */
    private boolean persistenceLoadFailed;

    public BotRuntime(MinecraftServer server) {
        this.server = server;
        int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        this.pathScheduler = new PathSearchScheduler(workers, 64);
        this.pathNodeBudget.beginTick(server.getTickCount());
        loadPersisted();
    }

    public MinecraftServer server() { return server; }
    public BotService service() { return this; }
    public NavigationMetrics metrics() { return metrics.snapshot(pathScheduler.queuedTasks(), pathScheduler.activeTasks()); }
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
        int tick = server.getTickCount();
        if (tick != snapshotBudgetTick) {
            snapshotBudgetTick = tick;
            snapshotsThisTick = 0;
        }
        if (snapshotsThisTick >= snapshotBudgetPerTick) return false;
        snapshotsThisTick++;
        return true;
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
        for (ChunkTicket ticket : desired) {
            if (tickets.add(ticket)) retainNavigationTicket(ticket);
        }
        for (ChunkTicket ticket : new HashSet<>(tickets)) {
            if (!desired.contains(ticket) && tickets.remove(ticket)) {
                releaseNavigationTicket(ticket);
            }
        }
        if (tickets.isEmpty()) navigationTickets.remove(botId, tickets);
    }

    private void retainNavigationTicket(ChunkTicket ticket) {
        int references = navigationTicketRefs.merge(ticket, 1, Integer::sum);
        if (references == 1) {
            ServerLevel level = server.getLevel(ticket.dimension());
            if (level != null) {
                level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_LOADING,
                        ChunkPos.unpack(ticket.chunk()), 2);
            }
        }
    }

    void releaseNavigationTickets(UUID botId) {
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
            ServerLevel level = server.getLevel(ticket.dimension());
            if (level != null) {
                level.getChunkSource().removeTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_LOADING,
                        ChunkPos.unpack(ticket.chunk()), 2);
            }
        }
    }

    @Override
    public BotHandle create(BotCreateOptions options) {
        ensureServerThread();
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
    @Override public Optional<BotHandle> find(String name) { return bots.values().stream().filter(b -> b.name().equalsIgnoreCase(name)).map(b -> (BotHandle)b).findFirst(); }
    @Override public Collection<BotHandle> bots() { return java.util.List.copyOf(bots.values()); }

    void remove(BotHandleImpl handle) {
        ensureServerThread();
        if (bots.remove(handle.id(), handle)) {
            releaseNavigationTickets(handle.id());
            ServerPlayer player = handle.player();
            connections.remove(player.getUUID());
            if (!player.isRemoved()) server.getPlayerList().remove(player);
        }
    }

    public void tick() {
        ensureServerThread();
        long started = System.nanoTime();
        try {
            updateAdaptiveBudgets();
            pathNodeBudget.beginTick(server.getTickCount(), adaptivePathNodeBudget);
            if (restoreDelayTicks > 0) {
                restoreDelayTicks--;
            } else {
                restorePendingNavigations();
            }
            bots.values().forEach(BotHandleImpl::tick);
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
        long lastTick = metrics.snapshot(pathScheduler.queuedTasks(), pathScheduler.activeTasks()).lastTickNanos();
        if (lastTick > 75_000_000L) {
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

    @Override public void close() {
        ensureServerThread();
        restorePendingNavigations();
        BotHandleImpl[] handles = bots.values().toArray(BotHandleImpl[]::new);
        for (BotHandleImpl handle : handles) {
            if (handle.state() != dev.navigationai.api.BotState.REMOVED) handle.prepareForShutdown();
        }
        savePersisted();
        for (BotHandleImpl handle : handles) {
            if (handle.state() != dev.navigationai.api.BotState.REMOVED) handle.removeForShutdown();
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
            root.putInt("version", 2);
            ValueOutput.ValueOutputList entries = root.childrenList("bots");
            for (BotHandleImpl handle : bots.values()) {
                ValueOutput entry = entries.addChild();
                ServerPlayer player = handle.player();
                entry.putString("name", handle.name());
                entry.putString("dimension", player.level().dimension().identifier().toString());
                player.saveWithoutId(entry);
                handle.navigatorImpl().saveState(entry.child("navigation"));
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
        if (pendingNavigations.isEmpty()) return;
        PendingNavigation[] pending = pendingNavigations.toArray(PendingNavigation[]::new);
        pendingNavigations.clear();
        for (PendingNavigation restore : pending) {
            try {
                if (!restore.handle().navigatorImpl().restoreState(restore.input())) {
                    LOGGER.warn("Skipping persisted navigation for bot {}: follow target is unavailable",
                            restore.handle().name());
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Skipping invalid persisted navigation for bot {}", restore.handle().name(), exception);
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
        entry.child("navigation").ifPresent(navigation -> pending.add(new PendingNavigation(handle, navigation)));
    }

    private record PendingNavigation(BotHandleImpl handle, ValueInput input) { }

    private record ChunkTicket(ResourceKey<Level> dimension, long chunk) { }

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
