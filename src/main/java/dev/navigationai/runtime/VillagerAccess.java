package dev.navigationai.runtime;

import net.minecraft.nbt.CompoundTag;

/** Per-entity attachment implemented by the vanilla villager mixin. */
public interface VillagerAccess {
    VillagerController navigationAiController();
    void navigationAiController(VillagerController controller);
    CompoundTag navigationAiSavedState();
    void navigationAiSavedState(CompoundTag state);
}
