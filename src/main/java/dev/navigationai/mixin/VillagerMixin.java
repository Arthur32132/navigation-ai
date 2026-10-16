package dev.navigationai.mixin;

import dev.navigationai.runtime.VillagerAccess;
import dev.navigationai.runtime.VillagerController;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Villager.class)
public abstract class VillagerMixin implements VillagerAccess {
    @Unique private VillagerController navigationAi$controller;
    @Unique private CompoundTag navigationAi$saved;
    @Override public VillagerController navigationAiController() { return navigationAi$controller; }
    @Override public void navigationAiController(VillagerController controller) { navigationAi$controller = controller; }
    @Override public CompoundTag navigationAiSavedState() { return navigationAi$saved; }
    @Override public void navigationAiSavedState(CompoundTag state) { navigationAi$saved = state; }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void navigationAi$save(ValueOutput output, CallbackInfo ci) {
        if (navigationAi$controller != null) navigationAi$controller.save(output.child("navigation_ai"));
        else if (navigationAi$saved != null) output.store("navigation_ai", CompoundTag.CODEC, navigationAi$saved);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void navigationAi$load(ValueInput input, CallbackInfo ci) {
        navigationAi$saved = input.read("navigation_ai", CompoundTag.CODEC).orElse(null);
    }

    @Inject(method = "wantsToPickUp", at = @At("HEAD"), cancellable = true)
    private void navigationAi$pickup(ServerLevel level, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (navigationAi$controller != null && navigationAi$controller.controlsBody()) cir.setReturnValue(false);
    }
}
