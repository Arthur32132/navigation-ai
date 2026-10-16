package dev.navigationai.runtime;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ProjectileWeaponItem;

/** Player-only vanilla operations stay here; geometry and movement use the real body. */
final class ActorSupport {
    private ActorSupport() { }

    static ServerPlayer context(LivingEntity entity) {
        if (entity instanceof ServerPlayer player) return player;
        if (entity instanceof VillagerAccess access && access.navigationAiController() != null) {
            return access.navigationAiController().context();
        }
        throw new IllegalStateException("Entity is not controlled by Navigation AI");
    }

    static boolean canSprint(LivingEntity entity) {
        return entity instanceof ServerPlayer player ? player.getFoodData().getFoodLevel() > 6
                : entity instanceof Villager && entity.isAlive();
    }

    static boolean mayInteract(LivingEntity entity, ServerLevel level, BlockPos pos) {
        return context(entity).mayInteract(level, pos);
    }
    static boolean mayBreak(LivingEntity entity,ServerLevel level,BlockPos pos){return mayInteract(entity,level,pos)
            &&dev.navigationai.api.BlockBreakPermissions.isAllowed(entity.getUUID(),pos);}

    static boolean sameBody(Entity first, Entity second) {
        return first == second || first.getUUID().equals(second.getUUID());
    }

    static void attack(LivingEntity actor, Entity target) {
        ServerPlayer context = context(actor);
        if (context instanceof VillagerActionPlayer villagerContext) villagerContext.synchronize();
        context.attack(target);
        if (actor != context) { actor.setLastHurtMob(target); context.resetAttackStrengthTicker(); }
    }

    static void releaseBow(LivingEntity actor) {
        if (actor instanceof ServerPlayer player) { player.releaseUsingItem(); return; }
        var bow = actor.getUseItem();
        if (bow.getItem() instanceof BowItem item && actor.level() instanceof ServerLevel level) {
            var ammo = context(actor).getProjectile(bow);
            float power = BowItem.getPowerForTime(actor.getTicksUsingItem());
            if (!ammo.isEmpty() && power >= .1f) {
                var projectiles = ProjectileWeaponItem.draw(bow, ammo, actor);
                item.shoot(level, actor, actor.getUsedItemHand(), bow, projectiles, power * 3, 1, power == 1, null);
                level.playSound(null, actor.getX(), actor.getY(), actor.getZ(),
                        net.minecraft.sounds.SoundEvents.ARROW_SHOOT, net.minecraft.sounds.SoundSource.NEUTRAL,
                        1, 1 / (level.getRandom().nextFloat() * .4f + 1.2f) + power * .5f);
            }
        }
        actor.stopUsingItem();
    }
}
