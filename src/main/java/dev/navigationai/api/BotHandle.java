package dev.navigationai.api;

import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

public interface BotHandle {
    UUID id();
    String name();
    ServerPlayer player();
    BotState state();
    BotActions actions();
    Navigator navigator();
    Miner miner();
    ResourceCollector collector();
    CombatController combat();
    void remove();
}
