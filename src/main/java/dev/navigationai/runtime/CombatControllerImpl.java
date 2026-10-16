package dev.navigationai.runtime;

import dev.navigationai.api.CombatController;
import dev.navigationai.api.CombatPolicy;
import dev.navigationai.api.CombatResult;
import dev.navigationai.api.CombatSkill;
import dev.navigationai.api.CombatStatus;
import dev.navigationai.api.CombatTask;
import dev.navigationai.api.CombatWeapon;
import dev.navigationai.api.CombatPhase;
import dev.navigationai.api.CombatDiagnostics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.component.DataComponents;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Comparator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
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
    private final BotRuntime runtime;
    private final BotHandleImpl owner;
    private CombatTaskImpl task;
    private LivingEntity target;
    private CombatPolicy policy;
    private CombatWeapon weapon = CombatWeapon.NONE;
    private int originalSlot = -1;
    private int swappedSlot = -1;
    private int swappedSelectedSlot = -1;
    private int reactionTicks;
    private int ticks;
    private int bowTicks;
    private int attacks;
    private int shots;
    private boolean bowUsing;
    private boolean nearbyMode;
    private double nearbyRadius;
    private int nearbyEmptyTicks;
    private long startGeneration;
    private int unseenTicks;
    private int weaponSwitches;
    private int lastWeaponSwitch;
    private int blockedMovementTicks;
    private boolean shieldUsing;
    private boolean visible;
    private double targetDistance = Double.NaN;
    private CombatPhase phase = CombatPhase.IDLE;

    CombatControllerImpl(BotRuntime runtime, BotHandleImpl owner) {
        this.runtime = runtime;
        this.owner = owner;
    }

    @Override public CombatTask fight(LivingEntity target, CombatPolicy policy) {
        return startFight(target, policy, true);
    }

    CombatTask fightAsChild(LivingEntity target, CombatPolicy policy) {
        return startFight(target, policy, false);
    }

    private CombatTask startFight(LivingEntity target, CombatPolicy policy, boolean root) {
        runtime.ensureServerThread();
        owner.ensureActive();
        if (target == null) throw new IllegalArgumentException("target is required");
        if (policy == null) throw new IllegalArgumentException("policy is required");
        if (!isAllowedTarget(target, policy)) throw new IllegalArgumentException("target type is not allowed by policy");
        if (root && owner.control().claim(BotControl.Kind.COMBAT) < 0) return superseded();
        long generation = ++startGeneration;
        long controlRequest = owner.control().generation();
        cancel();
        if (!cancelOtherControllers(generation) || !owner.control().current(controlRequest)) return superseded();
        owner.ensureActive();
        this.target = target;
        this.nearbyMode = false;
        this.nearbyRadius = 0.0;
        this.nearbyEmptyTicks = 0;
        this.policy = policy;
        this.originalSlot = ActorSupport.context(owner.entity()).getInventory().getSelectedSlot();
        this.reactionTicks = policy.skill().reactionTicks();
        this.ticks = 0;
        this.attacks = 0;
        this.shots = 0;
        this.bowTicks = 0;
        this.bowUsing = false;
        resetTactics();
        this.task = new CombatTaskImpl();
        updateDiagnostics("running");
        return task;
    }

    @Override public CombatTask fightNearby(double radius, CombatPolicy policy) {
        runtime.ensureServerThread();
        owner.ensureActive();
        if (policy == null) throw new IllegalArgumentException("policy is required");
        if (!(radius > 0.0 && radius <= policy.maxFollowDistance())) {
            throw new IllegalArgumentException("radius must be in (0, maxFollowDistance]");
        }
        if (owner.control().claim(BotControl.Kind.COMBAT) < 0) return superseded();
        long generation = ++startGeneration;
        long controlRequest = owner.control().generation();
        cancel();
        if (!cancelOtherControllers(generation) || !owner.control().current(controlRequest)) return superseded();
        owner.ensureActive();
        this.target = null;
        this.nearbyMode = true;
        this.nearbyRadius = radius;
        this.nearbyEmptyTicks = 0;
        this.policy = policy;
        this.originalSlot = ActorSupport.context(owner.entity()).getInventory().getSelectedSlot();
        this.reactionTicks = policy.skill().reactionTicks();
        this.ticks = 0;
        this.attacks = 0;
        this.shots = 0;
        this.bowTicks = 0;
        this.bowUsing = false;
        resetTactics();
        this.task = new CombatTaskImpl();
        updateDiagnostics("running");
        return task;
    }

    private void resetTactics() {
        unseenTicks = 0;
        weaponSwitches = 0;
        lastWeaponSwitch = -policy.tactics().weaponSwitchCooldownTicks();
        blockedMovementTicks = 0;
        shieldUsing = false;
        visible = false;
        targetDistance = Double.NaN;
        phase = CombatPhase.ACQUIRING;
    }

    private boolean cancelOtherControllers(long generation) {
        if (generation != startGeneration) return false;
        owner.navigator().cancel();
        if (generation != startGeneration) return false;
        if (owner.miner().task() != null) owner.miner().task().cancel();
        if (generation != startGeneration) return false;
        if (owner.collector().task() != null) owner.collector().task().cancel();
        return generation == startGeneration;
    }

    private CombatTask superseded() {
        CombatTaskImpl cancelled = new CombatTaskImpl();
        cancelled.cancel();
        return cancelled;
    }

    @Override public void cancel() {
        runtime.ensureServerThread();
        CombatTaskImpl cancelledTask = task;
        if (cancelledTask != null && cancelledTask.status == CombatStatus.RUNNING) cancelledTask.cancel();
        // A completion callback is allowed to start another task. Do not clear
        // that new task's state when the old cancellation returns.
        if (task != cancelledTask) return;
        clearCombatState();
    }

    private void clearCombatState() {
        stopBow();
        owner.actions().stop();
        restoreWeapon();
        target = null;
        weapon = CombatWeapon.NONE;
        nearbyMode = false;
        nearbyRadius = 0.0;
        nearbyEmptyTicks = 0;
    }

    @Override public CombatStatus status() { return task == null ? CombatStatus.IDLE : task.status; }
    @Override public CombatTask task() { return task; }
    @Override public LivingEntity target() { return target; }
    boolean isActive() { return task != null && task.status == CombatStatus.RUNNING; }
    int simulationRadius() {
        return isActive() && policy.allowsBow() ? Math.min(6, 2 + (int)Math.ceil(policy.bowRange() / 16)) : 2;
    }

    void tick() {
        if (!isActive()) return;
        ticks++;
        LivingEntity player = owner.entity();
        String stopReason = CombatDecision.stopReason(policy, ticks, 0, player.getHealth());
        if (stopReason != null) {
            finish(CombatResult.failure(stopReason, attacks, shots));
            return;
        }
        if (nearbyMode && (!isAllowedNearbyTarget(target) || !target.isAlive())) {
            clearCurrentTarget();
            target = findNearbyTarget();
            if (target == null) {
                nearbyEmptyTicks++;
                if (nearbyEmptyTicks > 100) finish(CombatResult.success(attacks, shots));
                else { phase = CombatPhase.ACQUIRING; updateDiagnostics("running"); }
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
                else { phase = CombatPhase.ACQUIRING; updateDiagnostics("running"); }
                return;
            }
            finish(CombatResult.failure("target_out_of_range", attacks, shots));
            return;
        }

        targetDistance = distance;
        visible = hasLineOfSight(target.getBoundingBox().getCenter());
        unseenTicks = visible ? 0 : unseenTicks + 1;
        stopReason = CombatDecision.stopReason(policy, ticks, unseenTicks, player.getHealth());
        if (stopReason != null) {
            finish(CombatResult.failure(stopReason, attacks, shots));
            return;
        }

        CombatWeapon desired = chooseWeapon(distance);
        if (desired == CombatWeapon.NONE) {
            finish(CombatResult.failure("no_combat_weapon", attacks, shots));
            return;
        }
        if ((desired != weapon || !isDesiredWeaponEquipped(desired)) && !equip(desired)) {
            finish(CombatResult.failure("weapon_not_available", attacks, shots));
            return;
        }

        boolean ranged = weapon == CombatWeapon.BOW;
        // Movement has a 0.55-block dead zone; keep its far edge inside reach.
        double desiredDistance = ranged ? Math.max(1.0, Math.min(policy.bowRange() - 1.0, 14.0))
                : Math.min(policy.meleeRange(), ActorSupport.context(player).entityInteractionRange()) - 0.75;
        Vec3 aim = aimPoint(ranged);
        lookSmoothlyAt(aim, policy.skill());
        moveAroundTarget(distance, desiredDistance);
        if (!visible) phase = CombatPhase.SEARCHING;
        boolean aimed = aimError(aim) <= policy.skill().aimTolerance();
        if (reactionTicks > 0) reactionTicks--;

        if (weapon == CombatWeapon.BOW) {
            stopShield();
            CombatTaskImpl current = task;
            tickBow(visible && hasLineOfSight(aim) && clearShot() && aimed && distance <= policy.bowRange());
            if (task != current || !isActive()) return;
            if (bowUsing) phase = CombatPhase.DRAWING;
        } else {
            tickShield(distance, visible && aimed);
            if (visible && aimed && rayHitsTarget() && reactionTicks == 0
                    && ActorSupport.context(player).getAttackStrengthScale(0.0f) >= 1.0f) {
                stopShield();
                // ServerPlayer.swing resets the attack-strength ticker in
                // 26.2. Apply the charged vanilla attack before its animation,
                // matching the normal attack-packet/hand-animation order.
                ActorSupport.attack(player, target);
                player.swing(InteractionHand.MAIN_HAND);
                attacks++;
                phase = CombatPhase.ATTACKING;
            } else if (shieldUsing) phase = CombatPhase.BLOCKING;
        }
        updateDiagnostics("running");
    }

    private CombatWeapon chooseWeapon(double distance) {
        Inventory inventory = ActorSupport.context(owner.entity()).getInventory();
        return CombatDecision.weapon(policy, weapon, distance,
                findBestSlot(inventory, CombatWeapon.SWORD) >= 0,
                findBestSlot(inventory, CombatWeapon.AXE) >= 0,
                hasUsableBow(inventory), target.isBlocking(), ticks - lastWeaponSwitch);
    }

    private boolean hasUsableBow(Inventory inventory) {
        return findBestSlot(inventory, CombatWeapon.BOW) >= 0;
    }

    private boolean equip(CombatWeapon desired) {
        if (findBestSlot(ActorSupport.context(owner.entity()).getInventory(), desired) < 0) return false;
        stopBow();
        restoreWeapon();
        Inventory inventory = ActorSupport.context(owner.entity()).getInventory();
        int slot = findBestSlot(inventory, desired);
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
            swappedSelectedSlot = selected;
        }
        if (weapon != desired) { weaponSwitches++; lastWeaponSwitch = ticks; }
        weapon = desired;
        owner.refreshEquipment();
        return true;
    }

    private void restoreWeapon() {
        if (swappedSlot >= 0) {
            Inventory inventory = ActorSupport.context(owner.entity()).getInventory();
            int selected = swappedSelectedSlot;
            ItemStack held = inventory.getItem(selected).copy();
            ItemStack old = inventory.getItem(swappedSlot).copy();
            inventory.setItem(selected, old);
            inventory.setItem(swappedSlot, held);
            swappedSlot = -1;
            swappedSelectedSlot = -1;
        }
        if (originalSlot >= 0) ActorSupport.context(owner.entity()).getInventory().setSelectedSlot(originalSlot);
        owner.refreshEquipment();
    }

    private int findBestSlot(Inventory inventory, CombatWeapon wanted) {
        int bestSlot = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int slot = 0; slot < usableInventorySize(inventory); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (classify(stack) != wanted) continue;
            if (stack.isBroken()) continue;
            if (wanted == CombatWeapon.BOW && !isUsableBow(stack)) continue;
            if (ActorSupport.context(owner.entity()).getCooldowns().isOnCooldown(stack)) continue;
            double score = wanted == CombatWeapon.SWORD || wanted == CombatWeapon.AXE ? swordScore(stack) : 0.0;
            if (bestSlot < 0 || score > bestScore) {
                bestSlot = slot;
                bestScore = score;
            }
        }
        return bestSlot;
    }

    private int usableInventorySize(Inventory inventory) {
        return Math.min(Inventory.INVENTORY_SIZE, inventory.getContainerSize());
    }

    private boolean isUsableBow(ItemStack stack) {
        if (!(stack.getItem() instanceof BowItem) || stack.isBroken()) return false;
        return !ActorSupport.context(owner.entity()).getProjectile(stack).isEmpty();
    }

    private double swordScore(ItemStack stack) {
        double attackDamage = 0.0;
        var modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers != null) {
            attackDamage = modifiers.compute(Attributes.ATTACK_DAMAGE, 0.0, EquipmentSlot.MAINHAND);
        }
        // Damage is a tie-breaker: a sword with higher attack damage remains
        // preferable, while among equal swords the less worn one wins.
        double durability = stack.isDamageableItem()
            ? (double) (stack.getMaxDamage() - stack.getDamageValue()) / Math.max(1, stack.getMaxDamage())
            : 1.0;
        return attackDamage * 1000.0 + durability;
    }

    private boolean isDesiredWeaponEquipped(CombatWeapon desired) {
        ItemStack held = owner.entity().getMainHandItem();
        if (classify(held) != desired) return false;
        if (ActorSupport.context(owner.entity()).getCooldowns().isOnCooldown(held)) return false;
        if (desired == CombatWeapon.BOW) return isUsableBow(held);
        int bestSlot = findBestSlot(ActorSupport.context(owner.entity()).getInventory(), desired);
        return bestSlot >= 0 && !held.isBroken()
            && swordScore(held) >= swordScore(ActorSupport.context(owner.entity()).getInventory().getItem(bestSlot));
    }

    private CombatWeapon classify(ItemStack stack) {
        if (stack.isEmpty()) return CombatWeapon.NONE;
        if (stack.getItem() instanceof BowItem) return CombatWeapon.BOW;
        if (stack.getItem() instanceof AxeItem) return CombatWeapon.AXE;
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return path.endsWith("_sword") ? CombatWeapon.SWORD : CombatWeapon.NONE;
    }

    private void tickBow(boolean aimed) {
        LivingEntity player = owner.entity();
        if (bowUsing && (!player.isUsingItem() || !(player.getUseItem().getItem() instanceof BowItem))) {
            bowUsing = false;
            bowTicks = 0;
        }
        if (!bowUsing) {
            if (!aimed || reactionTicks > 0) return;
            ItemStack bow = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (!(bow.getItem() instanceof BowItem)
                    || ActorSupport.context(player).getProjectile(bow).isEmpty()) {
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
                ActorSupport.releaseBow(player);
                shots++;
            } else {
                player.stopUsingItem();
            }
            bowUsing = false;
            bowTicks = 0;
        }
    }

    private void stopBow() {
        if (bowUsing || owner.entity().isUsingItem()) owner.entity().stopUsingItem();
        bowUsing = false;
        bowTicks = 0;
        shieldUsing = false;
    }

    private void tickShield(double distance, boolean facing) {
        LivingEntity player = owner.entity();
        ItemStack shield = player.getOffhandItem();
        boolean shouldBlock = policy.tactics().useShield() && facing
                && distance <= policy.meleeRange() + 1.5
                && ActorSupport.context(player).getAttackStrengthScale(0.0f) < 1.0f
                && !shield.isEmpty() && !shield.isBroken() && shield.has(DataComponents.BLOCKS_ATTACKS)
                && !ActorSupport.context(player).getCooldowns().isOnCooldown(shield);
        if (!shouldBlock) { stopShield(); return; }
        if (!shieldUsing || !player.isUsingItem()) player.startUsingItem(InteractionHand.OFF_HAND);
        shieldUsing = true;
    }

    private void stopShield() {
        if (shieldUsing) owner.entity().stopUsingItem();
        shieldUsing = false;
    }

    private void moveAroundTarget(double distance, double desiredDistance) {
        double forward = distance > desiredDistance + 0.55 ? 1.0 : distance < desiredDistance - 0.65 ? -0.65 : 0.0;
        double sideways = 0.0;
        if (Math.abs(distance - desiredDistance) <= 2.0) {
            sideways = ((ticks / policy.skill().strafePeriod()) & 1) == 0 ? 0.65 : -0.65;
        }
        boolean sprint = distance > desiredDistance + 4.0;
        boolean jump = forward > 0 && shouldJumpOverObstacle(distance, desiredDistance);
        boolean blocked = false;
        if (policy.tactics().safeMovement() && !safeMovement(sideways, forward, jump, sprint)) {
            blocked = true;
            // Test independent inputs: retreat must not be forced over a ledge
            // merely because a safe lateral escape is available beside it.
            if (forward != 0 && safeMovement(0, forward, jump, sprint)) sideways = 0;
            else if (safeMovement(sideways, 0, false, false) && sideways != 0) {
                forward = 0; jump = false; sprint = false;
            } else if (sideways != 0 && safeMovement(-sideways, 0, false, false)) {
                sideways = -sideways; forward = 0; jump = false; sprint = false;
            } else {
                sideways = 0; forward = 0; jump = false; sprint = false;
            }
        }
        if (blocked) blockedMovementTicks++;
        phase = forward < 0 ? CombatPhase.RETREATING : forward > 0 ? CombatPhase.APPROACHING
                : sideways != 0 ? CombatPhase.STRAFING : CombatPhase.HOLDING;
        owner.actions().setMovement((float) sideways, (float) forward, jump, sprint,
                blocked && sideways == 0 && forward == 0);
    }

    private boolean safeMovement(double sideways, double forward, boolean jump, boolean sprint) {
        if (sideways == 0 && forward == 0) return true;
        LivingEntity player = owner.entity();
        ServerLevel level = (ServerLevel) player.level();
        double yaw = Math.toRadians(player.getYRot());
        double dx = sideways * Math.cos(yaw) - forward * Math.sin(yaw);
        double dz = forward * Math.cos(yaw) + sideways * Math.sin(yaw);
        double length = Math.hypot(dx, dz);
        // Probe beyond the next physics step, including residual horizontal
        // velocity. This is a collision/terrain query, never a position change.
        double reach = (sprint ? 1.0 : 0.7) + Math.min(0.6, player.getDeltaMovement().horizontalDistance());
        dx = dx / length * reach;
        dz = dz / length * reach;
        for (int sample = 1; sample <= 3; sample++) {
            AABB body = player.getBoundingBox().move(dx * sample / 3.0, 0, dz * sample / 3.0);
            for (int x = (int) Math.floor(body.minX); x <= (int) Math.floor(body.maxX - 1.0E-6); x++) {
                for (int z = (int) Math.floor(body.minZ); z <= (int) Math.floor(body.maxZ - 1.0E-6); z++) {
                    for (int y = (int) Math.floor(body.minY) - 1; y <= (int) Math.floor(body.maxY); y++) {
                        BlockPos cell = new BlockPos(x, y, z);
                        if (!level.hasChunkAt(cell) || NavigationSafety.dangerous(level, cell)
                                || !level.getFluidState(cell).isEmpty()) return false;
                    }
                }
            }
            if (!level.noBlockCollision(player, body)) {
                if (!jump || !level.noBlockCollision(player, body.move(0, 1.01, 0))) return false;
            }
            // Every corner needs support, including partial blocks. A gap
            // under one side of the footprint is unsafe for strafing/backing.
            double drop = player.onGround() ? 0.6 : 1.5;
            for (double x : new double[]{body.minX + 0.035, body.maxX - 0.035}) {
                for (double z : new double[]{body.minZ + 0.035, body.maxZ - 0.035}) {
                    AABB support = new AABB(x - 0.02, body.minY - drop, z - 0.02,
                            x + 0.02, body.minY + 0.02, z + 0.02);
                    if (level.noBlockCollision(player, support)) return false;
                }
            }
        }
        return true;
    }

    private boolean shouldJumpOverObstacle(double distance, double desiredDistance) {
        LivingEntity player = owner.entity();
        if (!player.onGround()) return false;

        // Do not jump while backing away. When closing distance, probe the
        // target-facing space so the bot can jump before the collision flag is
        // set by the physics tick. A one-block step is clear at y + 1, while a
        // taller wall remains blocked and will not cause endless jumping.
        boolean targetHigher = target.getY() > player.getY() + 0.75;
        if (distance <= desiredDistance + 0.55 && !targetHigher && !player.horizontalCollision) return false;
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-4) return false;
        // Match the movement safety horizon. Otherwise the safe-movement check
        // stops short of the obstacle before this shorter jump probe ever
        // touches it, and the bot cannot climb an ordinary one-block step.
        double probeDistance = (distance > desiredDistance + 4.0 ? 1.0 : 0.7)
                + Math.min(0.6, player.getDeltaMovement().horizontalDistance());
        dx = dx / horizontal * probeDistance;
        dz = dz / horizontal * probeDistance;

        AABB probe = player.getBoundingBox().move(dx, 0.0, dz);
        if (player.level().noBlockCollision(player, probe)) return false;
        return player.level().noBlockCollision(player, probe.move(0.0, 1.01, 0.0));
    }

    private Vec3 aimPoint(boolean ranged) {
        Vec3 point = target.getBoundingBox().getCenter();
        if (ranged) {
            double speed = 3.0 * BowItem.getPowerForTime(policy.skill().bowDrawTicks());
            double flightTicks = owner.entity().distanceTo(target) / Math.max(0.1, speed);
            double leadTicks = Math.min(8.0, flightTicks);
            Vec3 velocity = target.getDeltaMovement();
            // Grounded entities retain downward gravity velocity even though
            // the floor prevents displacement. Do not lead below their feet.
            point = point.add(velocity.x * leadTicks,
                target.onGround() ? 0.0 : velocity.y * leadTicks, velocity.z * leadTicks);
            // Approximate vanilla arrow gravity (0.05 blocks/tick squared).
            // This changes aim only; the released arrow keeps vanilla physics.
            point = point.add(0.0, 0.025 * flightTicks * flightTicks, 0.0);
        }
        return point;
    }

    private void lookSmoothlyAt(Vec3 point, CombatSkill skill) {
        Vec3 from = owner.entity().getEyePosition();
        double dx = point.x - from.x;
        double dy = point.y - from.y;
        double dz = point.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float wantedYaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        float wantedPitch = (float) (Math.atan2(-dy, horizontal) * 180.0 / Math.PI);
        float yaw = stepAngle(owner.entity().getYRot(), wantedYaw, skill.yawStep());
        float pitch = stepLinear(owner.entity().getXRot(), wantedPitch, skill.pitchStep());
        owner.actions().look(yaw, pitch);
    }

    private boolean hasLineOfSight(Vec3 point) {
        ServerLevel level = (ServerLevel) owner.entity().level();
        Vec3 from = owner.entity().getEyePosition();
        HitResult hit = level.clip(new ClipContext(from, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner.entity()));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(from) + 1.0E-7 >= point.distanceToSqr(from);
    }

    private boolean rayHitsTarget() {
        Vec3 from = owner.entity().getEyePosition();
        double reach = Math.min(policy.meleeRange(), ActorSupport.context(owner.entity()).entityInteractionRange());
        Vec3 end = from.add(owner.entity().getViewVector(1.0f).scale(reach));
        return firstEntityOnSegment(from, end) == target
                && ActorSupport.context(owner.entity()).isWithinAttackRange(owner.entity().getMainHandItem(), target.getBoundingBox(), 0);
    }

    private boolean clearShot() {
        return firstEntityOnSegment(owner.entity().getEyePosition(), target.getBoundingBox().getCenter()) == target;
    }

    private Entity firstEntityOnSegment(Vec3 from, Vec3 end) {
        LivingEntity player = owner.entity();
        ServerLevel level = (ServerLevel) player.level();
        HitResult block = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double blockDistance = block.getType() == HitResult.Type.MISS ? Double.POSITIVE_INFINITY
                : from.distanceToSqr(block.getLocation());
        var hitboxes = level.getEntities(player, new AABB(from, end).inflate(1.0),
                entity -> entity.isPickable() && !entity.isSpectator())
                .stream().map(entity -> new CombatGeometry.Hitbox<>(entity,
                        entity.getBoundingBox().inflate(entity.getPickRadius()))).toList();
        return CombatGeometry.firstHit(from, end, blockDistance, hitboxes);
    }

    private double aimError(Vec3 point) {
        Vec3 from = owner.entity().getEyePosition();
        double dx = point.x - from.x;
        double dy = point.y - from.y;
        double dz = point.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float wantedYaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        float wantedPitch = (float) (Math.atan2(-dy, horizontal) * 180.0 / Math.PI);
        return Math.max(Math.abs(angleDelta(owner.entity().getYRot(), wantedYaw)), Math.abs(owner.entity().getXRot() - wantedPitch));
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
        if (candidate == null || candidate == owner.entity() || candidate.isRemoved()
                || owner.entity().isAlliedTo(candidate)) return false;
        if (candidate instanceof Player player) return policy.allowPlayers()
                && !player.isCreative() && !player.isSpectator() && ActorSupport.context(owner.entity()).canHarmPlayer(player);
        return candidate instanceof Mob && policy.allowMobs();
    }

    private boolean isAllowedNearbyTarget(LivingEntity candidate) {
        if (!isAllowedTarget(candidate, policy)) return false;
        return !(candidate instanceof Player player) || (!player.isCreative() && !player.isSpectator());
    }

    private LivingEntity findNearbyTarget() {
        LivingEntity player = owner.entity();
        AABB area = player.getBoundingBox().inflate(nearbyRadius);
        return player.level().getEntities(player, area, entity -> entity instanceof LivingEntity living
                && living.isAlive()
                && isAllowedNearbyTarget(living)
                && player.distanceToSqr(living) <= nearbyRadius * nearbyRadius)
            .stream()
            .map(entity -> (LivingEntity) entity)
            .min(Comparator.<LivingEntity, Boolean>comparing(entity -> !hasLineOfSight(entity.getBoundingBox().getCenter()))
                    .thenComparingDouble(player::distanceToSqr))
            .orElse(null);
    }

    private void clearCurrentTarget() {
        stopBow();
        owner.actions().stop();
        restoreWeapon();
        target = null;
        weapon = CombatWeapon.NONE;
        unseenTicks = 0;
        visible = false;
        targetDistance = Double.NaN;
        reactionTicks = policy.skill().reactionTicks();
    }

    private void finish(CombatResult result) {
        CombatTaskImpl finishedTask = task;
        if (finishedTask != null) finishedTask.snapshot = snapshot(result.status(), CombatPhase.FINISHED, result.reason());
        clearCombatState();
        // Clear controller state before completing the future. CompletableFuture
        // invokes callbacks synchronously when already complete; a callback may
        // start another fight and must not be overwritten by this cleanup.
        if (finishedTask != null) finishedTask.complete(result);
    }

    private void updateDiagnostics(String reason) {
        if (task != null && task.status == CombatStatus.RUNNING) task.snapshot = snapshot(task.status, phase, reason);
    }

    private CombatDiagnostics snapshot(CombatStatus status, CombatPhase phase, String reason) {
        return new CombatDiagnostics(status, phase, target == null ? null : target.getUUID(), weapon,
                ticks, unseenTicks, weaponSwitches, attacks, shots, blockedMovementTicks,
                targetDistance, visible, status == CombatStatus.RUNNING && shieldUsing, reason);
    }

    private final class CombatTaskImpl implements CombatTask {
        private final CompletableFuture<CombatResult> completion = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private CombatStatus status = CombatStatus.RUNNING;
        private CombatDiagnostics snapshot = new CombatDiagnostics(CombatStatus.RUNNING, CombatPhase.ACQUIRING,
                null, CombatWeapon.NONE, 0, 0, 0, 0, 0, 0, Double.NaN, false, false, "running");

        @Override public CombatStatus status() { return status; }
        @Override public CompletableFuture<CombatResult> completion() { return completion.copy(); }
        @Override public CombatDiagnostics diagnostics() { return snapshot; }
        @Override public void cancel() {
            runtime.ensureServerThread();
            if (cancelled.compareAndSet(false, true) && status == CombatStatus.RUNNING) {
                status = CombatStatus.CANCELLED;
                if (CombatControllerImpl.this.task == this) {
                    snapshot = CombatControllerImpl.this.snapshot(status, CombatPhase.FINISHED, "cancelled");
                    clearCombatState();
                } else {
                    snapshot = new CombatDiagnostics(status, CombatPhase.FINISHED, snapshot.targetId(), snapshot.weapon(),
                            snapshot.elapsedTicks(), snapshot.unseenTicks(), snapshot.weaponSwitches(), snapshot.attacks(),
                            snapshot.shots(), snapshot.blockedMovementTicks(), snapshot.targetDistance(),
                            snapshot.targetVisible(), false, "cancelled");
                }
                completion.complete(CombatResult.cancelled(snapshot.attacks(), snapshot.shots()));
            }
        }
        private void complete(CombatResult result) {
            if (status != CombatStatus.RUNNING) return;
            status = result.status();
            completion.complete(result);
        }
    }
}
