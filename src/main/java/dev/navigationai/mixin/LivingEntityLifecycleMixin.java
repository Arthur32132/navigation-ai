package dev.navigationai.mixin;

import dev.navigationai.runtime.VillagerAccess;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class LivingEntityLifecycleMixin {
    @Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"), cancellable = true)
    private void navigationAi$ledge(net.minecraft.world.phys.Vec3 movement, net.minecraft.world.entity.MoverType type,
                                   CallbackInfoReturnable<net.minecraft.world.phys.Vec3> cir) {
        if (type == net.minecraft.world.entity.MoverType.SELF && (Object) this instanceof VillagerAccess access
                && access.navigationAiController() != null) {
            cir.setReturnValue(access.navigationAiController().guardLedge(movement));
        }
    }
    @Inject(method = "setRemoved", at = @At("TAIL"))
    private void navigationAi$removed(Entity.RemovalReason reason, CallbackInfo ci) {
        if ((Object) this instanceof VillagerAccess access && access.navigationAiController() != null) {
            access.navigationAiController().entityRemoved();
        }
    }
}
