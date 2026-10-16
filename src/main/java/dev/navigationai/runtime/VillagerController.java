package dev.navigationai.runtime;

import dev.navigationai.api.BotState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Owns only control and a working backpack. Vanilla owns the real villager's physics and lifetime. */
public final class VillagerController {
    private Villager villager;
    private final VillagerActionPlayer context;
    private final BotHandleImpl handle;
    private boolean dropped;
    private float forwardInput, strafeInput;
    private boolean jumpInput, sprintInput, sneakInput;

    VillagerController(BotRuntime runtime, Villager villager, String name) {
        this.villager = villager;
        this.context = new VillagerActionPlayer(villager);
        ((VillagerAccess) villager).navigationAiController(this);
        context.getInventory().setItem(0, villager.getMainHandItem());
        this.handle = new BotHandleImpl(runtime, context, villager, name);
        handle.villagerController(this);
    }

    BotHandleImpl handle() { return handle; }
    void rebind(Villager replacement) {
        ((VillagerAccess) villager).navigationAiController(null);
        villager = replacement;
        ((VillagerAccess) replacement).navigationAiController(this);
        ((VillagerAccess) replacement).navigationAiSavedState(null);
        handle.rebindEntity(replacement);
        context.rebind(replacement);
        refreshEquipment();
    }
    VillagerActionPlayer context() { return context; }

    void beforeTick() {
        if (controlsBody() && villager.isSleeping()) villager.stopSleeping();
        context.tickContext();
        if (villager.isAlive() && controlsBody()) {
            for (ItemEntity item : villager.level().getEntitiesOfClass(ItemEntity.class,
                    villager.getBoundingBox().inflate(.5, 0, .5))) item.playerTouch(context);
        }
    }

    void afterTick() { refreshEquipment(); }

    void refreshEquipment() {
        villager.setItemSlot(EquipmentSlot.MAINHAND, context.getMainHandItem());
        context.synchronize();
    }

    /** Called in Mob.serverAiStep before vanilla controls can overwrite task input. */
    public boolean applyControlledMovement() {
        if (!controlsBody()) return false;
        if (villager.isSleeping()) villager.stopSleeping();
        villager.setSpeed((float) (.2 * villager.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)));
        villager.zza = forwardInput; villager.xxa = strafeInput;
        villager.setJumping(jumpInput); villager.setSprinting(sprintInput); villager.setShiftKeyDown(sneakInput);
        return true;
    }

    void movementInput(float strafe, float forward, boolean jump, boolean sprint, boolean sneak) {
        strafeInput = strafe; forwardInput = forward;
        jumpInput = jump; sprintInput = sprint; sneakInput = sneak;
    }

    public boolean controlsBody() {
        return handle != null && handle.state() == BotState.ACTIVE && handle.controlsBody();
    }
    public void entityRemoved() { handle.runtime().entityUnloaded(villager); }

    /** Mob bodies need explicit ledge braking, including ordinary travel beside a deep drop. */
    public net.minecraft.world.phys.Vec3 guardLedge(net.minecraft.world.phys.Vec3 movement) {
        // Native Brain resumes between jobs (including paid crafting). Its wandering must keep
        // the same physical cliff protection as library navigation, without taking over the Brain.
        if (handle == null || handle.state() != BotState.ACTIVE || villager.isInWater()) return movement;
        if (movement.y > 0 && controlsBody() && jumpInput) return movement;
        // onGround can briefly be false while vanilla still has support at a corner.
        // Check the actual floor too, before momentum can carry the body into open air.
        if (!villager.onGround() && unsupported(0, 0, .6)) return movement;
        double depth = sneakInput ? .6 : Math.max(.6, handle.navigatorImpl().ledgeDropDistance() + .1);
        double x = movement.x, z = movement.z;
        for (int attempt = 0; attempt < 64 && unsupported(x, 0, depth); attempt++) x = reduce(x);
        for (int attempt = 0; attempt < 64 && unsupported(0, z, depth); attempt++) z = reduce(z);
        for (int attempt = 0; attempt < 64 && unsupported(x, z, depth); attempt++) { x = reduce(x); z = reduce(z); }
        return new net.minecraft.world.phys.Vec3(x, movement.y, z);
    }
    private boolean unsupported(double x, double z, double depth) {
        var body = villager.getBoundingBox().move(x, 0, z);
        var feet = new net.minecraft.world.phys.AABB(body.minX + .01, body.minY - depth, body.minZ + .01,
                body.maxX - .01, body.minY + .01, body.maxZ - .01);
        return villager.level().noBlockCollision(villager, feet);
    }
    private static double reduce(double value) { return Math.copySign(Math.max(0, Math.abs(value) - .05), value); }

    public void save(ValueOutput output) {
        output.putInt("version", 1);
        output.putString("name", handle.name());
        output.putInt("selected", context.getInventory().getSelectedSlot());
        var items = output.childrenList("backpack");
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = context.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            var entry = items.addChild();
            entry.putInt("slot", slot); entry.store("stack", ItemStack.CODEC, stack);
        }
        handle.navigatorImpl().saveState(output.child("navigation"));
        handle.activitiesImpl().saveState(output.child("activity"));
    }

    CompoundTag snapshotState() {
        var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, villager.registryAccess());
        save(out);
        return out.buildResult();
    }

    void restore(CompoundTag saved) {
        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, villager.registryAccess(), saved);
        if (input.getIntOr("version", 0) != 1) throw new IllegalArgumentException("Unsupported villager controller data version");
        for (int slot = 0; slot < 36; slot++) context.getInventory().setItem(slot, ItemStack.EMPTY);
        for (ValueInput entry : input.childrenListOrEmpty("backpack")) {
            int slot = entry.getIntOr("slot", -1);
            if (slot < 0 || slot >= 36) throw new IllegalArgumentException("Invalid villager backpack slot");
            context.getInventory().setItem(slot, entry.read("stack", ItemStack.CODEC).orElseThrow());
        }
        int selected = input.getIntOr("selected", 0);
        if (selected < 0 || selected >= 9) throw new IllegalArgumentException("Invalid selected slot");
        context.getInventory().setSelectedSlot(selected);
        refreshEquipment();
        handle.runtime().restoreVillagerTasks(handle, input);
    }

    /** Backpack items are additional to vanilla equipment and the native villager food inventory. */
    public void dropBackpack() {
        if (dropped || !(villager.level() instanceof ServerLevel level)) return;
        dropped = true;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = context.getInventory().getItem(slot);
            if (!stack.isEmpty() && !net.minecraft.world.item.enchantment.EnchantmentHelper.has(stack,
                    net.minecraft.world.item.enchantment.EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
                villager.spawnAtLocation(level, stack.copy());
            }
            context.getInventory().setItem(slot, ItemStack.EMPTY);
        }
        villager.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        ((VillagerAccess) villager).navigationAiSavedState(null);
    }

    void release(boolean unloading) {
        villager.getNavigation().stop();
        villager.xxa = 0; villager.zza = 0; villager.setJumping(false);
        villager.setSprinting(false); villager.setShiftKeyDown(false); villager.stopUsingItem();
        if (!unloading) {
            // The real hand remains equipped; return all other working items as drops.
            int selected = context.getInventory().getSelectedSlot();
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = context.getInventory().getItem(slot);
                if (slot != selected && !stack.isEmpty()) villager.spawnAtLocation((ServerLevel) villager.level(), stack.copy());
                context.getInventory().setItem(slot, ItemStack.EMPTY);
            }
            ((VillagerAccess) villager).navigationAiSavedState(null);
        }
        ((VillagerAccess) villager).navigationAiController(null);
    }
}
