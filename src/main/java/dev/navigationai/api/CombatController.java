package dev.navigationai.api;

import net.minecraft.world.entity.LivingEntity;

public interface CombatController {
    CombatTask fight(LivingEntity target, CombatPolicy policy);
    CombatTask fightNearby(double radius, CombatPolicy policy);
    void cancel();
    CombatStatus status();
    CombatTask task();
    LivingEntity target();
}
