package dev.navigationai.runtime;

import dev.navigationai.api.Miner;
import dev.navigationai.api.MiningResult;
import dev.navigationai.api.MiningStatus;
import dev.navigationai.api.MiningTask;
import dev.navigationai.api.ExcavationPolicy;
import dev.navigationai.api.ExcavationRequest;
import dev.navigationai.api.ExcavationStatus;
import dev.navigationai.api.ExcavationTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;

final class MinerImpl implements Miner {
    private final BotRuntime runtime;
    private final BotHandleImpl owner;
    private MiningTaskImpl task;
    private final ExcavatorImpl excavator;
    private long startGeneration;

    MinerImpl(BotRuntime runtime, BotHandleImpl owner) {
        this.runtime = runtime;
        this.owner = owner;
        this.excavator = new ExcavatorImpl(runtime, owner, this);
    }

    @Override
    public MiningTask breakBlock(BlockPos position) {
        runtime.ensureServerThread();
        java.util.Objects.requireNonNull(position, "position");
        owner.ensureActive();
        if (owner.control().claim(BotControl.Kind.MINING) < 0) return superseded(position);
        long generation = ++startGeneration;
        long controlRequest = owner.control().generation();
        excavator.cancel();
        if (generation != startGeneration || !owner.control().current(controlRequest)) return superseded(position);
        return breakBlock(position, true);
    }

    @Override public ExcavationTask excavate(ExcavationRequest request, ExcavationPolicy policy) {
        runtime.ensureServerThread();
        owner.ensureActive();
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(policy, "policy");
        if (owner.control().claim(BotControl.Kind.MINING) < 0) return excavator.superseded(request, policy);
        return excavateAsChild(request, policy);
    }

    ExcavationTask excavateAsChild(ExcavationRequest request, ExcavationPolicy policy) {
        runtime.ensureServerThread();
        owner.ensureActive();
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(policy, "policy");
        long generation = ++startGeneration;
        long controlRequest = owner.control().generation();
        cancel();
        if (generation != startGeneration || !owner.control().current(controlRequest)) return excavator.superseded(request, policy);
        owner.navigator().cancel();
        if (generation != startGeneration || !owner.control().current(controlRequest)) return excavator.superseded(request, policy);
        return excavator.start(request, policy);
    }

    @Override public ExcavationStatus excavationStatus() { return excavator.status(); }
    @Override public ExcavationTask excavationTask() { return excavator.task(); }
    boolean isActive() { return status() == MiningStatus.RUNNING || excavationStatus() == ExcavationStatus.RUNNING; }

    MiningTask breakBlockAsChild(BlockPos position) {
        runtime.ensureServerThread();
        return breakBlock(position, true);
    }

    MiningTask breakBlockForCollector(BlockPos position) {
        runtime.ensureServerThread();
        return breakBlock(position, true, true);
    }

    MiningTask breakBlockForExcavator(BlockPos position, int minimumDurability, boolean claimDrops) {
        runtime.ensureServerThread();
        return breakBlock(position, true, claimDrops, minimumDurability);
    }

    MiningTask breakBlockWithoutCancellingNavigation(BlockPos position) {
        runtime.ensureServerThread();
        return breakBlock(position, false);
    }

    private MiningTask breakBlock(BlockPos position, boolean cancelNavigation) {
        return breakBlock(position, cancelNavigation, false);
    }

    private MiningTask breakBlock(BlockPos position, boolean cancelNavigation, boolean claimDrops) {
        return breakBlock(position, cancelNavigation, claimDrops, 1);
    }

    private MiningTask breakBlock(BlockPos position, boolean cancelNavigation, boolean claimDrops, int minimumDurability) {
        java.util.Objects.requireNonNull(position, "position");
        owner.ensureActive();
        long generation = ++startGeneration;
        long controlRequest = owner.control().generation();
        cancelBlock();
        if (generation != startGeneration || !owner.control().current(controlRequest)) return superseded(position);
        if (cancelNavigation) owner.navigator().cancel();
        if (generation != startGeneration || !owner.control().current(controlRequest)) return superseded(position);
        owner.ensureActive();
        task = new MiningTaskImpl(position.immutable(), claimDrops, minimumDurability);
        return task;
    }

    private MiningTask superseded(BlockPos position) {
        MiningTaskImpl cancelled = new MiningTaskImpl(position.immutable());
        cancelled.cancel();
        return cancelled;
    }

    @Override public MiningStatus status() { return task == null ? MiningStatus.IDLE : task.status; }
    @Override public MiningTask task() { return task; }

    void tick() {
        excavator.tick();
        MiningTaskImpl current = task;
        if (current == null || current.status != MiningStatus.RUNNING) return;
        current.ticks++;
        LivingEntity player = owner.entity();
        ServerLevel level = (ServerLevel) player.level();
        BlockPos position = current.position;
        if (!MiningSafety.loaded(level, position)) { current.fail("unloaded_chunk"); return; }
        BlockState state = level.getBlockState(position);
        if (current.ticks > 6_000) { current.fail("timeout"); return; }
        if (state.isAir()) { current.fail("block_missing"); return; }
        if (current.initialState != null && !state.equals(current.initialState)) {
            current.fail("block_changed"); return;
        }
        if (!ActorSupport.mayBreak(player, level, position)) { current.fail("not_allowed"); return; }
        if (MiningSafety.supportsPlayer(player, position)) { current.fail("unsafe_support"); return; }
        double reach = ActorSupport.context(player).getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
        Vec3 eye = player.getEyePosition();
        if (MiningAim.distanceToBlockSqr(position, eye) > reach * reach) { current.fail("out_of_range"); return; }
        Vec3 aim = MiningAim.visiblePoint(position, eye, reach, point -> {
            var hit = level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(position);
        });
        if(aim==null&&state.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)
                &&new net.minecraft.world.phys.AABB(position).intersects(player.getBoundingBox()))aim=Vec3.atCenterOf(position);
        if (aim == null) { current.fail("obstructed"); return; }
        lookAt(player, aim);
        if (current.ticks == 1) {
            current.initialState = state;
            int selected = selectTool(player, state, current.minimumDurability);
            if (selected < 0) { current.fail("no_suitable_tool"); return; }
            current.originalSlot = ActorSupport.context(player).getInventory().getSelectedSlot();
            current.toolSlot = selected;
            selectToolSlot(player, selected, current);
            current.toolPrepared = true;
        }
        ItemStack held = player.getMainHandItem();
        if (held.nextDamageWillBreak()) { current.fail("tool_worn_out"); return; }
        if (held.isDamageableItem() && held.getMaxDamage() - held.getDamageValue() <= current.minimumDurability) {
            current.fail("tool_worn_out"); return;
        }
        if (state.requiresCorrectToolForDrops() && !held.isCorrectToolForDrops(state)) { current.fail("tool_changed"); return; }
        float destroyProgress = state.getDestroyProgress(ActorSupport.context(player), level, position);
        if (destroyProgress <= 0.0f) {
            current.fail("cannot_break");
            return;
        }
        current.progress += destroyProgress;
        level.destroyBlockProgress(player.getId(), position, Math.min(9, (int)(current.progress * 10)));
        if (current.progress < 1.0f) {
            player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            return;
        }
        var dropArea = new net.minecraft.world.phys.AABB(position).inflate(2);
        java.util.Set<java.util.UUID> oldDrops = current.claimDrops
                ? level.getEntities(player, dropArea, entity -> entity instanceof net.minecraft.world.entity.item.ItemEntity)
                    .stream().map(net.minecraft.world.entity.Entity::getUUID).collect(java.util.stream.Collectors.toSet())
                : java.util.Set.of();
        boolean broken = ActorSupport.context(player).gameMode.destroyBlock(position);
        if (broken && current.claimDrops) {
            for (var entity : level.getEntities(player, dropArea, candidate -> candidate instanceof net.minecraft.world.entity.item.ItemEntity
                    && !oldDrops.contains(candidate.getUUID()))) {
                runtime.reserveDrop(owner.id(), (net.minecraft.world.entity.item.ItemEntity)entity);
            }
        }
        restoreToolSlot(player, current);
        // A waterlogged block leaves water behind when destroyed. The old
        // block must be gone, but the position need not become air.
        if (broken && level.getBlockState(position).getBlock() != state.getBlock()) current.complete();
        else current.fail("break_rejected");
    }

    void cancel() {
        runtime.ensureServerThread();
        long controlRequest = owner.control().generation();
        MiningTaskImpl oldBlock = task;
        excavator.cancel();
        // A cancellation callback may have installed a newer root intent.
        if (!owner.control().current(controlRequest) || task != oldBlock) return;
        cancelBlock();
    }

    private void cancelBlock() {
        if (task != null && task.status == MiningStatus.RUNNING) task.cancel();
    }

    private void lookAt(LivingEntity player, Vec3 to) {
        Vec3 from = player.getEyePosition();
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        float pitch = (float) (Math.atan2(-dy, horizontal) * 180.0 / Math.PI);
        owner.actions().look(yaw, pitch);
    }

    boolean hasUsableTool(BlockState state, int minimumDurability) {
        return selectTool(owner.entity(), state, minimumDurability) >= 0;
    }

    private int selectTool(LivingEntity player, BlockState state, int minimumDurability) {
        Inventory inventory = ActorSupport.context(player).getInventory();
        int selected = inventory.getSelectedSlot();
        int bestSlot = -1;
        float bestSpeed = -1.0f;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isCorrectToolForDrops(state) || stack.nextDamageWillBreak()
                    || stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= minimumDurability) continue;
            float speed = effectiveDestroySpeed(stack, state);
            if (bestSlot < 0 || speed > bestSpeed
                    || (speed == bestSpeed && slot == selected)) {
                bestSlot = slot;
                bestSpeed = speed;
            }
        }
        if (bestSlot >= 0) return bestSlot;
        if (state.requiresCorrectToolForDrops()) return -1;
        ItemStack held = inventory.getItem(selected);
        if (!held.isDamageableItem() || held.getMaxDamage() - held.getDamageValue() > minimumDurability) return selected;
        // Use an empty hand for dirt/wood rather than wearing out the last tool.
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) if (inventory.getItem(slot).isEmpty()) return slot;
        return -1;
    }

    /** Mirrors the vanilla Efficiency bonus when ranking tools before mining. */
    private float effectiveDestroySpeed(ItemStack stack, BlockState state) {
        float speed = stack.getDestroySpeed(state);
        if (speed <= 0.0f) return speed;
        int efficiency = 0;
        for (var entry : stack.getEnchantments().entrySet()) {
            if (entry.getKey().is(Enchantments.EFFICIENCY)) {
                efficiency = Math.max(efficiency, entry.getIntValue());
            }
        }
        return efficiency > 0 && speed > 1.0f ? speed + efficiency * efficiency + 1.0f : speed;
    }

    private void selectToolSlot(LivingEntity player, int slot, MiningTaskImpl current) {
        Inventory inventory = ActorSupport.context(player).getInventory();
        int selected = inventory.getSelectedSlot();
        if (slot >= Inventory.getSelectionSize()) {
            ItemStack held = inventory.getItem(selected).copy();
            ItemStack tool = inventory.getItem(slot).copy();
            inventory.setItem(selected, tool);
            inventory.setItem(slot, held);
            current.swapped = true;
        }
        else {
            inventory.setSelectedSlot(slot);
        }
        owner.refreshEquipment();
    }

    private void restoreToolSlot(LivingEntity player, MiningTaskImpl task) {
        if (!task.toolPrepared || task.toolRestored) return;
        Inventory inventory = ActorSupport.context(player).getInventory();
        int selected = task.originalSlot;
        if (task.swapped) {
            ItemStack held = inventory.getItem(selected).copy();
            ItemStack old = inventory.getItem(task.toolSlot).copy();
            inventory.setItem(selected, old);
            inventory.setItem(task.toolSlot, held);
        }
        inventory.setSelectedSlot(selected);
        task.toolSlot = -1;
        task.toolRestored = true;
        owner.refreshEquipment();
    }

    private final class MiningTaskImpl implements MiningTask {
        private final BlockPos position;
        private final boolean claimDrops;
        private final int minimumDurability;
        private final CompletableFuture<MiningResult> completion = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private MiningStatus status = MiningStatus.RUNNING;
        private int ticks;
        private int originalSlot = -1;
        private int toolSlot = -1;
        private boolean swapped;
        private boolean toolPrepared;
        private boolean toolRestored;
        private float progress;
        private BlockState initialState;
        private final long started = System.nanoTime();

        private MiningTaskImpl(BlockPos position) { this(position, false); }
        private MiningTaskImpl(BlockPos position, boolean claimDrops) { this(position, claimDrops, 1); }
        private MiningTaskImpl(BlockPos position, boolean claimDrops, int minimumDurability) {
            this.position = position; this.claimDrops = claimDrops; this.minimumDurability = minimumDurability;
        }
        @Override public MiningStatus status() { return status; }
        @Override public CompletableFuture<MiningResult> completion() { return completion.copy(); }
        @Override public void cancel() {
            runtime.ensureServerThread();
            if (cancelled.compareAndSet(false, true) && status == MiningStatus.RUNNING) {
                restoreToolSlot(playerForRestore(), this);
                clearCracks();
                status = MiningStatus.CANCELLED;
                completion.complete(MiningResult.cancelled());
            }
        }
        private LivingEntity playerForRestore() { return owner.entity(); }
        private void complete() {
            if (status != MiningStatus.RUNNING) return;
            clearCracks();
            status = MiningStatus.SUCCESS;
            completion.complete(MiningResult.success(System.nanoTime() - started));
        }
        private void fail(String reason) {
            if (status != MiningStatus.RUNNING) return;
            restoreToolSlot(owner.entity(), this);
            clearCracks();
            status = MiningStatus.FAILED;
            completion.complete(MiningResult.failure(reason, System.nanoTime() - started));
        }
        private void clearCracks() {
            if (owner.entity().level() instanceof ServerLevel level) level.destroyBlockProgress(owner.entity().getId(), position, -1);
        }
    }
}
