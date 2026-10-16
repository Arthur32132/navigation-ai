package dev.navigationai.api;

import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

public interface BotHandle {
    UUID id();
    String name();
    ServerPlayer player();
    /** Actual tracked body. player() is only valid for PLAYER handles. */
    default net.minecraft.world.entity.LivingEntity entity() { return player(); }
    default BotKind kind() { return BotKind.PLAYER; }
    /** Releases library control while keeping the entity alive. Supported by villagers. */
    default void detach() { throw new UnsupportedOperationException("Only villager handles can be detached"); }
    BotState state();
    BotActions actions();
    Navigator navigator();
    Miner miner();
    ResourceCollector collector();
    CombatController combat();
    default BotInventory inventory() {
        throw new UnsupportedOperationException("This bot does not support inventory operations");
    }
    default BotSnapshot snapshot() {
        throw new UnsupportedOperationException("This bot does not support snapshots");
    }
    /** Cancels all tasks. False means a completion callback started a newer intent. */
    default boolean stopAll() {
        throw new UnsupportedOperationException("This bot does not support stopping all tasks");
    }
    default ActivityController activities() {
        throw new UnsupportedOperationException("This bot does not support activity sequences");
    }
    void remove();
}
