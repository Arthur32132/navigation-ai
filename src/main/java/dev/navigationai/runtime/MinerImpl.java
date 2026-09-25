package dev.navigationai.runtime;

import dev.navigationai.api.Miner;
import dev.navigationai.api.MiningResult;
import dev.navigationai.api.MiningStatus;
import dev.navigationai.api.MiningTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

final class MinerImpl implements Miner {
    private final BotRuntime runtime;
    private final BotHandleImpl owner;
    private MiningTaskImpl task;

    MinerImpl(BotRuntime runtime, BotHandleImpl owner) {
        this.runtime = runtime;
        this.owner = owner;
    }

    @Override
    public MiningTask breakBlock(BlockPos position) {
        runtime.ensureServerThread();
        return breakBlock(position, true);
    }

    MiningTask breakBlockWithoutCancellingNavigation(BlockPos position) {
        runtime.ensureServerThread();
        return breakBlock(position, false);
    }

    private MiningTask breakBlock(BlockPos position, boolean cancelNavigation) {
        cancel();
        if (cancelNavigation) owner.navigator().cancel();
        task = new MiningTaskImpl(position.immutable());
        return task;
    }

    @Override public MiningStatus status() { return task == null ? MiningStatus.IDLE : task.status; }
    @Override public MiningTask task() { return task; }

    void tick() {
        if (task == null || task.status != MiningStatus.RUNNING) return;
        task.ticks++;
        ServerPlayer player = owner.player();
        ServerLevel level = (ServerLevel) player.level();
        BlockPos position = task.position;
        BlockState state = level.getBlockState(position);
        if (state.isAir()) { task.fail("block_missing"); return; }
        if (!player.mayInteract(level, position)) { task.fail("not_allowed"); return; }
        if (player.distanceToSqr(Vec3.atCenterOf(position)) > 36.0) { task.fail("out_of_range"); return; }
        lookAt(player, position);
        if (task.ticks == 1) {
            int selected = selectTool(player, state);
            if (selected < 0) { task.fail("no_suitable_tool"); return; }
            task.originalSlot = player.getInventory().getSelectedSlot();
            task.toolSlot = selected;
            selectToolSlot(player, selected);
            task.toolPrepared = true;
        }
        float destroyProgress = state.getDestroyProgress(player, level, position);
        if (destroyProgress <= 0.0f) {
            task.fail("cannot_break");
            return;
        }
        task.progress += destroyProgress;
        if (task.progress < 1.0f) {
            player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            return;
        }
        boolean broken = player.gameMode.destroyBlock(position);
        restoreToolSlot(player);
        // A waterlogged block leaves water behind when destroyed. The old
        // block must be gone, but the position need not become air.
        if (broken && level.getBlockState(position).getBlock() != state.getBlock()) task.complete();
        else task.fail("break_rejected");
    }

    void cancel() {
        runtime.ensureServerThread();
        if (task != null && task.status == MiningStatus.RUNNING) task.cancel();
    }

    private void lookAt(ServerPlayer player, BlockPos position) {
        Vec3 from = player.getEyePosition();
        Vec3 to = Vec3.atCenterOf(position);
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        float pitch = (float) (Math.atan2(-dy, horizontal) * 180.0 / Math.PI);
        owner.actions().look(yaw, pitch);
    }

    private int selectTool(ServerPlayer player, BlockState state) {
        Inventory inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        int bestSlot = -1;
        float bestSpeed = -1.0f;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isCorrectToolForDrops(state) || stack.nextDamageWillBreak()) continue;
            float speed = effectiveDestroySpeed(stack, state);
            if (bestSlot < 0 || speed > bestSpeed
                    || (speed == bestSpeed && slot == selected)) {
                bestSlot = slot;
                bestSpeed = speed;
            }
        }
        if (bestSlot >= 0) return bestSlot;
        return state.requiresCorrectToolForDrops() ? -1 : selected;
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
        return efficiency > 0 ? speed + efficiency * efficiency + 1.0f : speed;
    }

    private void selectToolSlot(ServerPlayer player, int slot) {
        Inventory inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        if (slot >= Inventory.getSelectionSize()) {
            ItemStack held = inventory.getItem(selected).copy();
            ItemStack tool = inventory.getItem(slot).copy();
            inventory.setItem(selected, tool);
            inventory.setItem(slot, held);
            task.swapped = true;
        }
        else {
            inventory.setSelectedSlot(slot);
        }
    }

    private void restoreToolSlot(ServerPlayer player) {
        if (!task.toolPrepared || task.toolRestored) return;
        Inventory inventory = player.getInventory();
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
    }

    private final class MiningTaskImpl implements MiningTask {
        private final BlockPos position;
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
        private final long started = System.nanoTime();

        private MiningTaskImpl(BlockPos position) { this.position = position; }
        @Override public MiningStatus status() { return status; }
        @Override public CompletableFuture<MiningResult> completion() { return completion; }
        @Override public void cancel() {
            runtime.ensureServerThread();
            if (cancelled.compareAndSet(false, true) && status == MiningStatus.RUNNING) {
                restoreToolSlot(playerForRestore());
                status = MiningStatus.CANCELLED;
                completion.complete(MiningResult.cancelled());
            }
        }
        private ServerPlayer playerForRestore() { return owner.player(); }
        private void complete() {
            if (status != MiningStatus.RUNNING) return;
            status = MiningStatus.SUCCESS;
            completion.complete(MiningResult.success(System.nanoTime() - started));
        }
        private void fail(String reason) {
            if (status != MiningStatus.RUNNING) return;
            restoreToolSlot(owner.player());
            status = MiningStatus.FAILED;
            completion.complete(MiningResult.failure(reason, System.nanoTime() - started));
        }
    }
}
