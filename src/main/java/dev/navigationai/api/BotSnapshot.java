package dev.navigationai.api;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/** Immutable observation captured on the server thread, safe to pass to other threads. */
public record BotSnapshot(UUID id, String name, BotState state, String dimension,
                          Vec3 position, float yaw, float pitch, float health, int foodLevel,
                          NavigationStatus navigation, MiningStatus mining, ExcavationStatus excavation, GatherStatus gathering,
                          CombatStatus combat, ActivityStatus activity, InventorySnapshot inventory) { }
