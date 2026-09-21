package dev.navigationai;

import dev.navigationai.runtime.BotRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fabric entrypoint. The public API is exposed through {@link BotRuntime#service()}. */
public final class NavigationAiMod implements ModInitializer {
    public static final String MOD_ID = "navigation_ai";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static BotRuntime runtime;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> { if (runtime == null) runtime = new BotRuntime(server); });
        ServerTickEvents.START_SERVER_TICK.register(server -> { if (runtime != null) runtime.tick(); });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (runtime != null) { runtime.close(); runtime = null; } });
    }

    public static synchronized BotRuntime runtime(MinecraftServer server) {
        if (runtime == null) runtime = new BotRuntime(server);
        if (runtime.server() != server) throw new IllegalStateException("Navigation AI is not active for this server");
        return runtime;
    }
}
