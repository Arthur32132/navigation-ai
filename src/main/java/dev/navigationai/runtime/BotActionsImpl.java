package dev.navigationai.runtime;

import dev.navigationai.api.BotActions;
import net.minecraft.server.level.ServerPlayer;

final class BotActionsImpl implements BotActions {
    private final ServerPlayer player;
    BotActionsImpl(ServerPlayer player) { this.player = player; }
    @Override public ServerPlayer player() { return player; }
    @Override public void stop() { setMovement(0, 0, false, false, false); }
    @Override public void setMovement(float sideways, float forward, boolean jump, boolean sprint, boolean sneak) {
        player.xxa = Math.max(-1, Math.min(1, sideways));
        player.zza = Math.max(-1, Math.min(1, forward));
        player.setJumping(jump);
        player.setSprinting(sprint);
        player.setShiftKeyDown(sneak);
    }
    @Override public void look(float yaw, float pitch) {
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(Math.max(-90, Math.min(90, pitch)));
    }
}
