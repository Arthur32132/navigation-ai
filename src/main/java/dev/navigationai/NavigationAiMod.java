package dev.navigationai;

import dev.navigationai.runtime.BotRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.TicketType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fabric entrypoint. The public API is exposed through {@link BotRuntime#service()}. */
public final class NavigationAiMod implements ModInitializer {
    public static final String MOD_ID = "navigation_ai";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    // TicketStorage deduplicates by type identity and level, without ownership.
    // Using PLAYER_LOADING here would let route cleanup remove a player's ticket.
    public static final TicketType NAVIGATION_TICKET = Registry.register(
            BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, "navigation"),
            new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING));
    public static final TicketType VILLAGER_TICKET = Registry.register(
            BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, "villager_control"),
            new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION
                    | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));
    private static volatile BotRuntime runtime;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (runtime == null) runtime = new BotRuntime(server);
            for (var level : server.getAllLevels()) for (var entity : level.getAllEntities()) runtime.entityLoaded(entity);
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> { if (runtime != null) runtime.entityLoaded(entity); });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> { if (runtime != null) runtime.entityUnloaded(entity); });
        ServerTickEvents.START_SERVER_TICK.register(server -> { if (runtime != null) runtime.tick(); });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (runtime != null) { runtime.close(); runtime = null; } });
    }

    public static synchronized BotRuntime runtime(MinecraftServer server) {
        if (runtime == null) runtime = new BotRuntime(server);
        if (runtime.server() != server) throw new IllegalStateException("Navigation AI is not active for this server");
        return runtime;
    }
}
