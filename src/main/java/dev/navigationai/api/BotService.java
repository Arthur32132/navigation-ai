package dev.navigationai.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public interface BotService {
    BotHandle create(BotCreateOptions options);
    /** Creates a real vanilla villager with all library controllers. */
    default BotHandle createVillager(BotCreateOptions options) {
        throw new UnsupportedOperationException("This service does not support villagers");
    }
    /** Attaches to a loaded villager, preserving its identity, trades and profession. */
    default BotHandle attach(net.minecraft.world.entity.npc.villager.Villager villager) {
        throw new UnsupportedOperationException("This service does not support villagers");
    }
    Optional<BotHandle> find(UUID id);
    Optional<BotHandle> find(String name);
    Collection<BotHandle> bots();
    /** Queues work on the server thread and propagates its result or exception.
     * Cancelling before dispatch skips the operation. Running operations are not interrupted. */
    default <T> CompletableFuture<T> submit(Function<BotService, T> operation) {
        throw new UnsupportedOperationException("This service does not support server-thread dispatch");
    }
}
