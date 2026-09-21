package dev.navigationai.runtime;

import dev.navigationai.api.BotActions;
import dev.navigationai.api.BotHandle;
import dev.navigationai.api.BotState;
import dev.navigationai.api.Miner;
import dev.navigationai.api.Navigator;
import dev.navigationai.api.ResourceCollector;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

final class BotHandleImpl implements BotHandle {
    private final BotRuntime runtime;
    private final ServerPlayer player;
    private final String name;
    private final BotActionsImpl actions;
    private final NavigatorImpl navigator;
    private final MinerImpl miner;
    private final ResourceCollectorImpl collector;
    private BotState state = BotState.ACTIVE;

    BotHandleImpl(BotRuntime runtime, ServerPlayer player, String name) {
        this.runtime = runtime;
        this.player = player;
        this.name = name;
        this.actions = new BotActionsImpl(player);
        this.navigator = new NavigatorImpl(runtime, this);
        this.miner = new MinerImpl(runtime, this);
        this.collector = new ResourceCollectorImpl(runtime, this);
    }
    BotRuntime runtime() { return runtime; }
    @Override public UUID id() { return player.getUUID(); }
    @Override public String name() { return name; }
    @Override public ServerPlayer player() { return player; }
    @Override public BotState state() { return state; }
    @Override public BotActions actions() { return actions; }
    @Override public Navigator navigator() { return navigator; }
    @Override public Miner miner() { return miner; }
    @Override public ResourceCollector collector() { return collector; }
    MinerImpl minerImpl() { return miner; }
    @Override public void remove() {
        navigator.cancel();
        runtime.remove(this);
        state = BotState.REMOVED;
    }
    void tick() {
        if (state == BotState.REMOVED) return;
        if (player.isDeadOrDying() || player.isRemoved()) {
            state = BotState.DEAD;
            navigator.cancel();
            miner.cancel();
            collector.cancel();
            return;
        }
        // Vanilla sends a hit player's knockback to that player's client and
        // restores the old server velocity afterwards. Reapply the captured
        // packet so a fake player keeps the same server-side knockback.
        runtime.applyPendingMotion(player);
        collector.tick();
        miner.tick();
        navigator.tick();
        // A fake player has no network connection registered with ServerConnectionListener.
        // Run the same server-side simulation step that a real player's connection invokes.
        player.doTick();
    }
}
