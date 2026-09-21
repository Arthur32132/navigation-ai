package dev.navigationai.api;

import net.minecraft.server.level.ServerPlayer;

/** Direct, server-thread actions. Implementations must be called on the server thread. */
public interface BotActions {
    ServerPlayer player();
    void stop();
    void setMovement(float sideways, float forward, boolean jump, boolean sprint, boolean sneak);
    void look(float yaw, float pitch);
}
