package dev.navigationai.runtime;

import dev.navigationai.api.CombatController;
import dev.navigationai.api.CombatPolicy;
import dev.navigationai.api.CombatResult;
import dev.navigationai.api.CombatSkill;
import dev.navigationai.api.CombatStatus;
import dev.navigationai.api.CombatTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Comparator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ClipContext;

/**
 * A deliberately server-side combat loop. It never applies damage directly:
 * melee damage goes through ServerPlayer.attack and arrows go through BowItem,
 * so vanilla cooldowns, armor, enchantments and projectile physics remain in
 * charge. The controller only attacks after its real view is aligned and the
 * target is visible.
 */
final class CombatControllerImpl implements CombatController {
    private enum Weapon { NONE, SWORD, AXE, BOW }

    private final BotRuntime runtime;
    private final BotHandleImpl owner;
    private CombatTaskImpl task;
    private LivingEntity target;
    private CombatPolicy policy;
    private Weapon weapon = Weapon.NONE;
    private int weaponSlot = -1;
    private int originalSlot = -1;
    private int swappedSlot = -1;
    private int reactionTicks;
    private int ticks;
    private int bowTicks;
    private int attacks;
    private int shots;
    private boolean bowUsing;
    private boolean nearbyMode;
    private double nearbyRadius;
    private int nearbyEmptyTicks;

    CombatControllerImpl(BotRuntime runtime, BotHandleImpl owner) {
        this.runtime = runtime;
        this.owner = owner;
    }

    @Override public CombatTask fight(LivingEntity target, CombatPolicy policy) {
        runtime.ensureServerThread();
        if (target == null) throw new IllegalArgumentException("target is required");
        if (policy == null) throw new IllegalArgumentException("policy is required");
        cancel();
        if (!isAllowedTarget(target, policy)) throw new IllegalArgumentException("target type is not allowed by policy");
        this.target = target;
        this.nearbyMode = false;
        this.nearbyRadius = 0.0;
        this.nearbyEmptyTicks = 0;
        this.policy = policy;
        this.originalSlot = owner.player().getInventory().getSelectedSlot();
        this.reactionTicks = policy.skill().reactionTicks();
        this.ticks = 0;
        this.attacks = 0;
        this.shots = 0;
        this.bowTicks = 0;
        this.bowUsing = false;
        this.task = new CombatTaskImpl();
        owner.navigator().cancel();
        if (owner.miner().task() != null) owner.miner().task().cancel();
        if (owner.collector().task() != null) owner.collector().task().cancel();
        return task;
    }

    @Override public CombatTask fightNearby(double radius, CombatPolicy policy) {
        runtime.ensureServerThread();
        if (policy == null) throw new IllegalArgumentException("policy is required");
        if (!(radius > 0.0 && radius <= policy.maxFollowDistance())) {
            throw new IllegalArgumentException("radius must be in (0, maxFollowDistance]");
        }
        cancel();
        this.target = null;
        this.nearbyMode = true;
        this.nearbyRadius = radius;
        this.nearbyEmptyTicks = 0;
        this.policy = policy;
        this.originalSlot = owner.player().getInventory().getSelectedSlot();
        this.reactionTicks = policy.skill().reactionTicks();
        this.ticks = 0;
        this.attacks = 0;
        this.shots = 0;
        this.bowTicks = 0;
        this.bowUsing = false;
        this.task = new CombatTaskImpl();
        owner.navigator().cancel();
        if (owner.miner().task() != null) owner.miner().task().cancel();
        if (owner.collector().task() != null) owner.collector().task().cancel();
        return task;
    }

    @Override public void cancel() {
        if (task != null && task.status == CombatStatus.RUNNING) task.cancel();
        stopBow();
        owner.actions().stop();
        restoreWeapon();
        target = null;
        weapon = Weapon.NONE;
        weaponSlot = -1;
        nearbyMode = false;
        nearbyRadius = 0.0;
        nearbyEmptyTicks = 0;
    }

    @Override public CombatStatus status() { return task == null ? CombatStatus.IDLE : task.status; }
    @Override public CombatTask task() { return task; }
    @Override public LivingEntity target() { return target; }
    boolean isActive() { return task != null && task.status == CombatStatus.RUNNING; }

    void tick() {
        if (!isActive()) return;
        ticks++;
        ServerPlayer player = owner.player();
        if (nearbyMode && (!isAllowedNearbyTarget(target) || !target.isAlive())) {
            clearCurrentTarget();
            target = findNearbyTarget();
            if (target == null) {
                nearbyEmptyTicks++;
                if (nearbyEmptyTicks > 100) finish(CombatResult.success(attacks, shots));
                return;
            }
            nearbyEmptyTicks = 0;
        } else if (!nearbyMode && (!isAllowedTarget(target, policy) || !target.isAlive())) {
            finish(target != null && !target.isAlive() ? CombatResult.success(attacks, shots) : CombatResult.failure("target_unavailable", attacks, shots));
            return;
        }
        if (target.level() != player.level()) {
            finish(CombatResult.failure("different_dimension", attacks, shots));
            return;
        }
        double distance = player.distanceTo(target);
        if (distance > (nearbyMode ? nearbyRadius : policy.maxFollowDistance())) {
            if (nearbyMode) {
                clearCurrentTarget();
                target = findNearbyTarget();
                if (target != null) {
                    nearbyEmptyTicks = 0;
                    return;
                }
                nearbyEmptyTicks++;
                if (nearbyEmptyTicks > 100) finish(CombatResult.success(attacks, shots));
                return;
            }
            finish(CombatResult.failure("target_out_of_range", attacks, shots));
            return;
        }

        Weapon desired = chooseWeapon(distance);
        if (desired == Weapon.NONE) {
            finish(CombatResult.failure("no_combat_weapon", attacks, shots));
            return;
        }
        if (desired != weapon && !equip(desired)) {
            finish(CombatResult.failure("weapon_not_available", attacks, shots));
            return;
        }

        boolean ranged = weapon == Weapon.BOW;
        double desiredDistance = ranged ? Math.min(policy.bowRange() - 2.0, 14.0) : policy.meleeRange() - 0.35;
        moveAroundTarget(distance, desiredDistance);
        Vec3 aim = aimPoint(ranged);
        lookSmoothlyAt(aim, policy.skill());
        boolean visible = hasLineOfSight(aim);
        boolean aimed = aimError(aim) <= policy.skill().aimTolerance();
        if (reactionTicks > 0) reactionTicks--;

        if (weapon == Weapon.BOW) {
            tickBow(visible && aimed);
        } else if (visible && aimed && rayHitsTarget() && reactionTicks == 0 && distance <= policy.meleeRange()
                && player.getAttackStrengthScale(0.0f) >= 1.0f) {
            player.attack(target);
            attacks++;
        }
    }

    private Weapon chooseWeapon(double distance) {
        Inventory inventory = owner.player().getInventory();
        if (distance > policy.meleeRange() * 1.35 && policy.allowBow() && hasUsableBow(inventory)) return Weapon.BOW;
        if (findSlot(inventory, Weapon.SWORD) >= 0) return Weapon.SWORD;
        if (findSlot(inventory, Weapon.AXE) >= 0) return Weapon.AXE;
        if (policy.allowBow() && hasUsableBow(inventory)) return Weapon.BOW;
        return Weapon.NONE;
    }

    private boolean hasUsableBow(Inventory inventory) {
        int slot = findSlot(inventory, Weapon.BOW);
        if (slot < 0) return false;
        ItemStack bow = inventory.getItem(slot);
        if (!(bow.getItem() instanceof BowItem bowItem)) return false;
        return !ProjectileWeaponItem.getHeldProjectile(owner.player(), bowItem.getAllSupportedProjectiles()).isEmpty();
    }

    private boolean equip(Weapon desired) {
        if (findSlot(owner.player().getInventory(), desired) < 0) return false;
        restoreWeapon();
        Inventory inventory = owner.player().getInventory();
        int slot = findSlot(inventory, desired);
        if (slot < 0) return false;
        if (slot < Inventory.getSelectionSize()) {
            inventory.setSelectedSlot(slot);
        } else {
            int selected = inventory.getSelectedSlot();
            ItemStack held = inventory.getItem(selected).copy();
            ItemStack replacement = inventory.getItem(slot).copy();
            inventory.setItem(selected, replacement);
            inventory.setItem(slot, held);
            swappedSlot = slot;
        }
        weapon = desired;
        weaponSlot = slot;
        return true;
    }

    private void restoreWeapon() {
        if (swappedSlot >= 0) {
            Inventory inventory = owner.player().getInventory();
            int selected = inventory.getSelectedSlot();
            ItemStack held = inventory.getItem(selected).copy();
            ItemStack old = inventory.getItem(swappedSlot).copy();
            inventory.setItem(selected, old);
            inventory.setItem(swappedSlot, held);
            swappedSlot = -1;
        }
        if (originalSlot >= 0) owner.player().getInventory().setSelectedSlot(originalSlot);
    }

    private int findSlot(Inventory inventory, Weapon wanted) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (classify(stack) == wanted) return slot;
        }
        return -1;
    }

    private Weapon classify(ItemStack stack) {
        if (stack.isEmpty()) return Weapon.NONE;
        if (stack.getItem() instanceof BowItem) return Weapon.BOW;
        if (stack.getItem() instanceof AxeItem) return Weapon.AXE;
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return path.endsWith("_sword") ? Weapon.SWORD : Weapon.NONE;
    }

    private void tickBow(boolean aimed) {
        ServerPlayer player = owner.player();
        if (!bowUsing) {
            if (!aimed || reactionTicks > 0) return;
            ItemStack bow = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (!(bow.getItem() instanceof BowItem bowItem)
                    || ProjectileWeaponItem.getHeldProjectile(player, bowItem.getAllSupportedProjectiles()).isEmpty()) {
                finish(CombatResult.failure("no_arrows", attacks, shots));
                return;
            }
            player.startUsingItem(InteractionHand.MAIN_HAND);
            bowUsing = true;
            bowTicks = 0;
            return;
        }
        bowTicks++;
        int maxDraw = player.getUseItem().getUseDuration(player) - 1;
        if (bowTicks >= policy.skill().bowDrawTicks() || bowTicks >= maxDraw) {
            if (aimed) {
                player.releaseUsingItem();
                shots++;
            } else {
                player.stopUsingItem();
            }
            bowUsing = false;
            bowTicks = 0;
        }
    }

    private void stopBow() {
        if (bowUsing || owner.player().isUsingItem()) owner.player().stopUsingItem();
        bowUsing = false;
        bowTicks = 0;
    }

    private void moveAroundTarget(double distance, double desiredDistance) {
        double forward = distance > desiredDistance + 0.55 ? 1.0 : distance < desiredDistance - 0.65 ? -0.65 : 0.0;
        double sideways = 0.0;
        if (Math.abs(distance - desiredDistance) <= 2.0) {
            sideways = ((ticks / policy.skill().strafePeriod()) & 1) == 0 ? 0.65 : -0.65;
        }
        boolean sprint = distance > desiredDistance + 4.0;
        owner.actions().setMovement((float) sideways, (float) forward, false, sprint, false);
    }

    private Vec3 aimPoint(boolean ranged) {
        Vec3 point = target.getBoundingBox().getCenter();
        if (ranged) {
            double leadTicks = Math.min(8.0, Math.max(0.0, owner.player().distanceTo(target) / 3.0));
            point = point.add(target.getDeltaMovement().scale(leadTicks));
        }
        return point;
    }

    private void lookSmoothlyAt(Vec3 point, CombatSkill skill) {
        Vec3 from = owner.player().getEyePosition();
        double dx = point.x - from.x;
        double dy = point.y - from.y;
        double dz = point.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float wantedYaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        float wantedPitch = (float) (Math.atan2(-dy, horizontal) * 180.0 / Math.PI);
        float yaw = stepAngle(owner.player().getYRot(), wantedYaw, skill.yawStep());
        float pitch = stepLinear(owner.player().getXRot(), wantedPitch, skill.pitchStep());
        owner.actions().look(yaw, pitch);
    }

    private boolean hasLineOfSight(Vec3 point) {
        ServerLevel level = (ServerLevel) owner.player().level();
        Vec3 from = owner.player().getEyePosition();
        HitResult hit = level.clip(new ClipContext(from, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner.player()));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(from) + 0.25 >= point.distanceToSqr(from);
    }

    private boolean rayHitsTarget() {
        Vec3 from = owner.player().getEyePosition();
        Vec3 end = from.add(owner.player().getViewVector(1.0f).scale(policy.meleeRange() + 1.0));
        return target.getBoundingBox().inflate(0.12).clip(from, end).isPresent();
    }

    private double aimError(Vec3 point) {
        Vec3 from = owner.player().getEyePosition();
        double dx = point.x - from.x;
        double dy = point.y - from.y;
        double dz = point.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float wantedYaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        float wantedPitch = (float) (Math.atan2(-dy, horizontal) * 180.0 / Math.PI);
        return Math.max(Math.abs(angleDelta(owner.player().getYRot(), wantedYaw)), Math.abs(owner.player().getXRot() - wantedPitch));
    }

    private static float stepAngle(float current, float wanted, float step) {
        float delta = angleDelta(current, wanted);
        if (Math.abs(delta) <= step) return wanted;
        return current + Math.copySign(step, delta);
    }

    private static float stepLinear(float current, float wanted, float step) {
        float delta = wanted - current;
        if (Math.abs(delta) <= step) return wanted;
        return current + Math.copySign(step, delta);
    }

    private static float angleDelta(float current, float wanted) {
        float delta = (wanted - current) % 360.0f;
        if (delta >= 180.0f) delta -= 360.0f;
        if (delta < -180.0f) delta += 360.0f;
        return delta;
    }

    private boolean isAllowedTarget(LivingEntity candidate, CombatPolicy policy) {
        if (candidate == null || candidate == owner.player()) return false;
        if (candidate instanceof Player) return policy.allowPlayers();
        return candidate instanceof Mob && policy.allowMobs();
    }

    private boolean isAllowedNearbyTarget(LivingEntity candidate) {
        if (!isAllowedTarget(candidate, policy)) return false;
        return !(candidate instanceof Player player) || (!player.isCreative() && !player.isSpectator());
    }

    private LivingEntity findNearbyTarget() {
        ServerPlayer player = owner.player();
        AABB area = player.getBoundingBox().inflate(nearbyRadius);
        return player.level().getEntities(player, area, entity -> entity instanceof LivingEntity living
                && living.isAlive()
                && isAllowedNearbyTarget(living)
                && player.distanceToSqr(living) <= nearbyRadius * nearbyRadius)
            .stream()
            .map(entity -> (LivingEntity) entity)
            .min(Comparator.comparingDouble(player::distanceToSqr))
            .orElse(null);
    }

    private void clearCurrentTarget() {
        stopBow();
        owner.actions().stop();
        restoreWeapon();
        weapon = Weapon.NONE;
        weaponSlot = -1;
        reactionTicks = policy.skill().reactionTicks();
    }

    private void finish(CombatResult result) {
        stopBow();
        owner.actions().stop();
        restoreWeapon();
        if (task != null) task.complete(result);
        target = null;
        weapon = Weapon.NONE;
        weaponSlot = -1;
        nearbyMode = false;
        nearbyRadius = 0.0;
        nearbyEmptyTicks = 0;
    }

    private final class CombatTaskImpl implements CombatTask {
        private final CompletableFuture<CombatResult> completion = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private CombatStatus status = CombatStatus.RUNNING;

        @Override public CombatStatus status() { return status; }
        @Override public CompletableFuture<CombatResult> completion() { return completion; }
        @Override public void cancel() {
            if (cancelled.compareAndSet(false, true) && status == CombatStatus.RUNNING) {
                status = CombatStatus.CANCELLED;
                completion.complete(CombatResult.cancelled(attacks, shots));
                stopBow();
                owner.actions().stop();
                restoreWeapon();
                target = null;
                weapon = Weapon.NONE;
                weaponSlot = -1;
                nearbyMode = false;
                nearbyRadius = 0.0;
                nearbyEmptyTicks = 0;
            }
        }
        private void complete(CombatResult result) {
            if (status != CombatStatus.RUNNING) return;
            status = result.status();
            completion.complete(result);
        }
    }
}
