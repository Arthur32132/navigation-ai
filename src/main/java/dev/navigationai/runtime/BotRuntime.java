package dev.navigationai.runtime;

import com.mojang.authlib.GameProfile;
import dev.navigationai.api.BotCreateOptions;
import dev.navigationai.api.BotHandle;
import dev.navigationai.api.BotService;
import dev.navigationai.mixin.PlayerListAccessor;
import java.util.Collection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.core.registries.Registries;
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
    private final MinecraftServer server;
    private final Map<UUID, BotHandleImpl> bots = new ConcurrentHashMap<>();
    private final ExecutorService pathExecutor;

    public BotRuntime(MinecraftServer server) {
        this.server = server;
        int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        this.pathExecutor = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "navigation-ai-path");
            thread.setDaemon(true);
            return thread;
        });
        loadPersisted();
    }

    public MinecraftServer server() { return server; }
    public BotService service() { return this; }
    ExecutorService pathExecutor() { return pathExecutor; }

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

        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new BotPacketListener(server, connection, player, CommonListenerCookie.createInitial(profile, false));
        player.initInventoryMenu();

        options.level().addNewPlayer(player);
        PlayerListAccessor list = (PlayerListAccessor)(Object)server.getPlayerList();
        list.navigationAi$players().add(player);
        list.navigationAi$playersByUuid().put(player.getUUID(), player);
        server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(java.util.List.of(player)));

        BotHandleImpl handle = new BotHandleImpl(this, player, options.name());
        bots.put(player.getUUID(), handle);
        LOGGER.info("Created navigation bot {} ({})", options.name(), player.getUUID());
        return handle;
    }

    @Override public Optional<BotHandle> find(UUID id) { return Optional.ofNullable(bots.get(id)).map(b -> b); }
    @Override public Optional<BotHandle> find(String name) { return bots.values().stream().filter(b -> b.name().equalsIgnoreCase(name)).map(b -> (BotHandle)b).findFirst(); }
    @Override public Collection<BotHandle> bots() { return java.util.List.copyOf(bots.values()); }

    void remove(BotHandleImpl handle) {
        ensureServerThread();
        if (bots.remove(handle.id(), handle)) {
            ServerPlayer player = handle.player();
            PlayerListAccessor list = (PlayerListAccessor)(Object)server.getPlayerList();
            list.navigationAi$players().remove(player);
            list.navigationAi$playersByUuid().remove(player.getUUID());
            server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(java.util.List.of(player.getUUID())));
            if (!player.isRemoved()) player.level().removePlayerImmediately(player, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        }
    }

    public void tick() {
        ensureServerThread();
        bots.values().forEach(BotHandleImpl::tick);
    }

    private void ensureServerThread() { if (!server.isSameThread()) throw new IllegalStateException("Bot API must be called on the server thread"); }

    @Override public void close() {
        savePersisted();
        BotHandleImpl[] handles = bots.values().toArray(BotHandleImpl[]::new);
        for (BotHandleImpl handle : handles) {
            if (!handle.player().isRemoved()) remove(handle);
        }
        pathExecutor.shutdownNow();
    }

    private Path persistencePath() { return server.getWorldPath(LevelResource.ROOT).resolve("navigation_ai_bots.dat"); }

    private void savePersisted() {
        try {
            Files.createDirectories(persistencePath().getParent());
            TagValueOutput root = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
            root.putInt("version", 1);
            ValueOutput.ValueOutputList entries = root.childrenList("bots");
            for (BotHandleImpl handle : bots.values()) {
                ValueOutput entry = entries.addChild();
                ServerPlayer player = handle.player();
                entry.putString("name", handle.name());
                entry.putString("dimension", player.level().dimension().identifier().toString());
                player.saveWithoutId(entry);
            }
            NbtIo.writeCompressed(root.buildResult(), persistencePath());
        } catch (Exception exception) {
            LOGGER.warn("Could not save navigation bots", exception);
        }
    }

    private void loadPersisted() {
        Path path = persistencePath();
        if (!Files.exists(path)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), root);
            input.childrenListOrEmpty("bots").stream().forEach(this::restoreEntry);
        } catch (Exception exception) {
            LOGGER.warn("Could not load navigation bots; keeping the save for manual recovery", exception);
        }
    }

    private void restoreEntry(ValueInput entry) {
        String name = entry.getStringOr("name", "bot");
        String dimensionId = entry.getStringOr("dimension", Level.OVERWORLD.identifier().toString());
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimensionId));
        var level = server.getLevel(key);
        if (level == null) { LOGGER.warn("Skipping bot {}: dimension {} is unavailable", name, dimensionId); return; }
        UUID uuid = entry.read("UUID", net.minecraft.core.UUIDUtil.CODEC).orElse(UUID.randomUUID());
        Vec3 position = entry.read("Pos", Vec3.CODEC).orElse(Vec3.ZERO);
        Vec2 rotation = entry.read("Rotation", Vec2.CODEC).orElse(Vec2.ZERO);
        BotHandle handle = create(new BotCreateOptions(name, uuid, level, position, rotation.x, rotation.y));
        handle.player().load(entry);
    }

    /** A disconnected connection is sufficient for server simulation and never queues packets. */
    private static final class BotPacketListener extends ServerGamePacketListenerImpl {
        private BotPacketListener(MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
            super(server, connection, player, cookie);
        }
        @Override public void send(net.minecraft.network.protocol.Packet<?> packet) { }
        @Override public void send(net.minecraft.network.protocol.Packet<?> packet, io.netty.channel.ChannelFutureListener listener) { }
    }
}
