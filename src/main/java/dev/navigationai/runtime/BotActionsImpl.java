package dev.navigationai.runtime;

import dev.navigationai.api.BotActions;
import net.minecraft.server.level.ServerPlayer;

final class BotActionsImpl implements BotActions {
    private final BotRuntime runtime;
    private final BotHandleImpl owner;
    BotActionsImpl(BotRuntime runtime, BotHandleImpl owner) {
        this.runtime = runtime;
        this.owner = owner;
    }
    private boolean manualMovement;
    boolean manualMovement() { return manualMovement; }
    @Override public ServerPlayer player() { return owner.player(); }
    @Override public net.minecraft.world.entity.LivingEntity entity() { return owner.entity(); }
    @Override public void stop() {
        runtime.ensureServerThread();
        applyMovement(0, 0, false, false, false);
        entity().setSwimming(false);
    }
    @Override public void setMovement(float sideways, float forward, boolean jump, boolean sprint, boolean sneak) {
        runtime.ensureServerThread();
        owner.ensureActive();
        if (!Float.isFinite(sideways) || !Float.isFinite(forward)) {
            throw new IllegalArgumentException("Movement input must be finite");
        }
        applyMovement(sideways, forward, jump, sprint, sneak);
    }
    private void applyMovement(float sideways, float forward, boolean jump, boolean sprint, boolean sneak) {
        var player = entity();
        manualMovement = sideways != 0 || forward != 0 || jump || sprint || sneak;
        player.xxa = Math.max(-1, Math.min(1, sideways));
        player.zza = Math.max(-1, Math.min(1, forward));
        player.setJumping(jump);
        player.setSprinting(sprint);
        player.setShiftKeyDown(sneak);
        owner.movementInput(player.xxa, player.zza, jump, sprint, sneak);
    }
    @Override public void look(float yaw, float pitch) {
        runtime.ensureServerThread();
        owner.ensureActive();
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Rotation must be finite");
        }
        float normalizedYaw = net.minecraft.util.Mth.wrapDegrees(yaw);
        var player = entity();
        player.setYRot(normalizedYaw);
        player.setYHeadRot(normalizedYaw);
        player.setXRot(Math.max(-90, Math.min(90, pitch)));
        if (player instanceof net.minecraft.world.entity.Mob mob) mob.yBodyRot = normalizedYaw;
    }
}
