package dev.navigationai.mixin;

import dev.navigationai.runtime.VillagerAccess;
import net.minecraft.world.entity.Mob;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mob.class)
public abstract class MobControlMixin {
    @Inject(method = "isEffectiveAi", at = @At("HEAD"), cancellable = true)
    private void navigationAi$enabledControls(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof VillagerAccess access && access.navigationAiController() != null
                && access.navigationAiController().controlsBody()) {
            cir.setReturnValue(!((Mob)(Object)this).level().isClientSide());
        }
    }
    @Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
    private void navigationAi$controls(CallbackInfo ci) {
        if ((Object) this instanceof VillagerAccess access && access.navigationAiController() != null
                && access.navigationAiController().applyControlledMovement()) ci.cancel();
    }

    @Inject(method = "dropCustomDeathLoot", at = @At("HEAD"))
    private void navigationAi$dropBackpack(ServerLevel level, DamageSource damage, boolean killedByPlayer, CallbackInfo ci) {
        if ((Object) this instanceof VillagerAccess access && access.navigationAiController() != null) {
            access.navigationAiController().dropBackpack();
        }
    }
}
