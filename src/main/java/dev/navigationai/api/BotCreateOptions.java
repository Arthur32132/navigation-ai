package dev.navigationai.api;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Immutable spawn and identity options for a bot. */
public record BotCreateOptions(String name, UUID uuid, ServerLevel level, Vec3 position, float yaw, float pitch) {
    public BotCreateOptions {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        if (name.isBlank() || name.length() > 16) throw new IllegalArgumentException("Bot name must contain 1..16 characters");
    }

    public static BotCreateOptions at(String name, ServerLevel level, Vec3 position) {
        return new BotCreateOptions(name, UUID.randomUUID(), level, position, 0, 0);
    }

    public BotCreateOptions withRotation(float yaw, float pitch) {
        return new BotCreateOptions(name, uuid, level, position, yaw, pitch);
    }
}
