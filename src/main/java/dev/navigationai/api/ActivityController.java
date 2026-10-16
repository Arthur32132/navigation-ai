package dev.navigationai.api;

import java.util.List;
import net.minecraft.world.entity.LivingEntity;

/** Server-thread API for sequences with explicit, bounded combat interruptions. */
public interface ActivityController {
    ActivityTask run(List<BotActivity> steps);
    default ActivityTask run(BotActivity... steps) { return run(List.of(steps)); }
    CombatTask interruptForCombat(LivingEntity target, CombatPolicy policy, int timeoutTicks);
    ActivityTask task();
    ActivityStatus status();
    void cancel();
}
