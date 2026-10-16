package dev.navigationaitest;

import dev.navigationai.NavigationAiMod;
import dev.navigationai.api.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Exercises the consumer-facing library API in a disposable real server world. */
final class LibraryApiRegression {
    private static LibraryApiRegression active;
    private static final String[] NAMES = {
            "inventory_snapshot_defensive_copy", "equipment_swap_preserves_items",
            "invalid_equipment_is_transactional", "stop_all_cancels_root_and_children",
            "stop_all_preserves_callback_intent", "submit_runs_on_server_thread",
            "submit_reports_exception", "submit_cancel_before_dispatch",
            "wait_then_excavate_then_return", "wait_save_restore_progress",
            "inventory_rejects_wrong_thread", "removed_bot_rejects_mutation",
            "excavation_save_at_exact_budget", "excavation_restore_preserves_block_budget",
            "delayed_restore_preserves_new_intent"};
    private final CommandSourceStack source;
    private final ServerLevel level;
    private final NavigationPolicy safe = new NavigationPolicy(false, false, false, false,
            20_000, 512, 16, 100, 3);
    private BotHandle bot;
    private ActivityTask activity;
    private NavigationTask navigation;
    private int scenario = -1, ticks, failed;
    private boolean condition;
    private volatile CompletableFuture<Boolean> submitted;
    private final AtomicBoolean invoked = new AtomicBoolean();

    private LibraryApiRegression(CommandSourceStack source) {
        this.source = source;
        this.level = source.getLevel();
    }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(
                Commands.literal("navlibrary").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(command -> start(command.getSource()))));
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (active != null) active.tick(); });
    }

    private static int start(CommandSourceStack source) {
        if (active != null) return 0;
        NavigationAiMod.runtime(source.getServer()).service().bots().forEach(BotHandle::remove);
        active = new LibraryApiRegression(source);
        active.next();
        return 1;
    }

    private BotService service() { return NavigationAiMod.runtime(source.getServer()).service(); }
    private BlockPos p(int x, int y, int z) { return new BlockPos(9000 + x, 200 + y, 9000 + z); }
    private Vec3 point(int x) { return Vec3.atBottomCenterOf(p(x, 0, 0)); }
    private void say(String message) { source.sendSystemMessage(Component.literal("LIBRARY " + message)); }

    private int countAll(net.minecraft.world.item.Item item) {
        int count = bot.player().getInventory().getNonEquipmentItems().stream()
                .filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot == EquipmentSlot.MAINHAND) continue;
            ItemStack stack = bot.player().getItemBySlot(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private void next() {
        if (bot != null) bot.remove();
        if (++scenario == NAMES.length) {
            say((failed == 0 ? "PASS" : "FAIL") + " passed=" + (NAMES.length - failed) + " failed=" + failed);
            active = null;
            return;
        }
        ticks = 0; condition = false; activity = null; navigation = null; submitted = null; invoked.set(false);
        for (int x = -2; x <= 12; x++) for (int z = -2; z <= 2; z++) {
            level.setBlock(p(x, -1, z), Blocks.BEDROCK.defaultBlockState(), 3);
            for (int y = 0; y <= 3; y++) level.setBlock(p(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        bot = service().create(BotCreateOptions.at("library_worker", level, point(0)));
        bot.player().getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
        say("START " + NAMES[scenario]);
        try {
            switch (scenario) {
                case 0 -> snapshotCopies();
                case 1 -> equipSwap();
                case 2 -> invalidEquip();
                case 3 -> {
                    activity = bot.activities().run(List.of(new BotActivity.Navigate(
                            new NavigationGoal.PositionGoal(point(10), .75), safe)));
                }
                case 4 -> callbackIntent();
                case 5 -> Thread.startVirtualThread(() -> submitted = service().submit(api ->
                        source.getServer().isSameThread() && api.find(bot.id()).orElseThrow() == bot));
                case 6 -> submitted = service().submit(api -> { throw new IllegalArgumentException("library_expected"); });
                case 7 -> {
                    submitted = service().submit(api -> { invoked.set(true); return true; });
                    condition = submitted.cancel(false);
                }
                case 8 -> excavationSequence();
                case 9 -> activity = bot.activities().run(List.of(new BotActivity.Wait(40),
                        new BotActivity.Navigate(new NavigationGoal.PositionGoal(point(0), .75), safe)));
                case 10 -> Thread.startVirtualThread(() -> {
                    try { bot.inventory().select(1); submitted = CompletableFuture.completedFuture(false); }
                    catch (IllegalStateException expected) { submitted = CompletableFuture.completedFuture(true); }
                    catch (Throwable error) { submitted = CompletableFuture.failedFuture(error); }
                });
                case 11 -> {
                    bot.remove();
                    try { bot.inventory().select(1); }
                    catch (IllegalStateException expected) { condition = true; }
                }
                case 12 -> excavationSequence();
                case 13 -> excavationSequence(3);
                case 14 -> delayedRestore();
            }
        } catch (Exception failure) { finish(false, "setup_exception=" + failure); }
    }

    private void snapshotCopies() {
        bot.player().getInventory().setItem(10, new ItemStack(Items.COBBLESTONE, 7));
        var inventory = bot.inventory().snapshot();
        ItemStack copy = inventory.slots().get(10);
        copy.setCount(1);
        condition = bot.inventory().count(Items.COBBLESTONE) == 7;
        bot.player().getInventory().getItem(10).setCount(9);
        condition &= inventory.slots().get(10).getCount() == 7;
        var snapshot = bot.snapshot();
        bot.actions().look(80, 25);
        condition &= snapshot.id().equals(bot.id()) && snapshot.name().equals(bot.name())
                && snapshot.position().equals(point(0));
    }

    private void equipSwap() {
        bot.player().getInventory().setItem(12, new ItemStack(Items.DIAMOND_HELMET));
        bot.player().setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        int diamonds = countAll(Items.DIAMOND_HELMET), leather = countAll(Items.LEATHER_HELMET);
        condition = bot.inventory().equip(12, EquipmentSlot.HEAD)
                && bot.player().getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET)
                && bot.player().getInventory().getItem(12).is(Items.LEATHER_HELMET)
                && countAll(Items.DIAMOND_HELMET) == diamonds && countAll(Items.LEATHER_HELMET) == leather;
        condition &= bot.inventory().select(12) && bot.player().getMainHandItem().is(Items.LEATHER_HELMET)
                && bot.player().getInventory().getItem(12).is(Items.DIAMOND_PICKAXE)
                && bot.inventory().freeSlots() == 34
                && countAll(Items.DIAMOND_HELMET) == diamonds && countAll(Items.LEATHER_HELMET) == leather;
    }

    private void invalidEquip() {
        bot.player().getInventory().setItem(12, new ItemStack(Items.COBBLESTONE, 7));
        bot.player().setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        condition = !bot.inventory().equip(12, EquipmentSlot.HEAD);
        condition &= countAll(Items.COBBLESTONE) == 7 && countAll(Items.LEATHER_HELMET) == 1
                && bot.player().getInventory().getItem(12).getCount() == 7
                && bot.player().getItemBySlot(EquipmentSlot.HEAD).is(Items.LEATHER_HELMET);
    }

    private void callbackIntent() {
        GatherTask gathering = bot.collector().gather(new GatherRequest(Blocks.STONE, Items.COBBLESTONE, 1),
                new GatherPolicy(4, 200, 2), safe);
        gathering.completion().thenRun(() -> navigation = bot.navigator().goTo(
                new NavigationGoal.PositionGoal(point(10), .75), safe));
        boolean stopped = bot.stopAll();
        condition = !stopped && gathering.status() == GatherStatus.CANCELLED && navigation != null
                && navigation.status() != NavigationStatus.CANCELLED && bot.navigator().goal().isPresent();
    }

    private void excavationSequence() {
        excavationSequence(6);
    }

    private void excavationSequence(int maxBlocks) {
        for (int x = 1; x <= 3; x++) for (int y = 0; y <= 1; y++) {
            level.setBlock(p(x, y, 0), Blocks.STONE.defaultBlockState(), 3);
        }
        var request = new ExcavationRequest(p(0, 0, 0), Direction.EAST, ExcavationMode.TUNNEL, 3);
        var policy = new ExcavationPolicy(maxBlocks, 800, 1, true, false);
        activity = bot.activities().run(List.of(new BotActivity.Wait(5), new BotActivity.Excavate(request, policy),
                new BotActivity.Navigate(new NavigationGoal.ReturnGoal(point(0), .75), safe)));
    }

    private void activityRoundTrip() throws Exception {
        Object controller = bot.activities();
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, source.getServer().registryAccess());
        var shutdown = controller.getClass().getDeclaredMethod("shutdown"); shutdown.setAccessible(true); shutdown.invoke(controller);
        var save = controller.getClass().getDeclaredMethod("saveState", net.minecraft.world.level.storage.ValueOutput.class);
        save.setAccessible(true); save.invoke(controller, output);
        var input = net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,
                source.getServer().registryAccess(), output.buildResult());
        bot.activities().cancel();
        var restore = controller.getClass().getDeclaredMethod("restoreState", net.minecraft.world.level.storage.ValueInput.class);
        restore.setAccessible(true); restore.invoke(controller, input);
        activity = bot.activities().task();
    }

    @SuppressWarnings("unchecked")
    private void delayedRestore() throws Exception {
        bot.navigator().goTo(new NavigationGoal.PositionGoal(point(10), .75), safe);
        Object controller = bot.navigator();
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, source.getServer().registryAccess());
        var save = controller.getClass().getDeclaredMethod("saveState", net.minecraft.world.level.storage.ValueOutput.class);
        save.setAccessible(true); save.invoke(controller, output);
        var input = net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,
                source.getServer().registryAccess(), output.buildResult());
        var controlMethod = bot.getClass().getDeclaredMethod("control"); controlMethod.setAccessible(true);
        Object control = controlMethod.invoke(bot);
        var generationMethod = control.getClass().getDeclaredMethod("generation"); generationMethod.setAccessible(true);
        long generation = (long)generationMethod.invoke(control);
        var requested = new NavigationGoal.PositionGoal(point(6), .75);
        navigation = bot.navigator().goTo(requested, safe);
        Object runtime = NavigationAiMod.runtime(source.getServer());
        Class<?> pendingClass = Class.forName("dev.navigationai.runtime.BotRuntime$PendingNavigation");
        var constructor = pendingClass.getDeclaredConstructor(bot.getClass(),
                net.minecraft.world.level.storage.ValueInput.class, long.class);
        constructor.setAccessible(true);
        var pendingField = runtime.getClass().getDeclaredField("pendingNavigations"); pendingField.setAccessible(true);
        ((List<Object>)pendingField.get(runtime)).add(constructor.newInstance(bot, input, generation));
        var restore = runtime.getClass().getDeclaredMethod("restorePendingNavigations"); restore.setAccessible(true); restore.invoke(runtime);
        condition = bot.navigator().goal().filter(requested::equals).isPresent()
                && navigation.status() != NavigationStatus.CANCELLED && !navigation.completion().isDone();
    }

    private void tick() {
        try {
            ticks++;
            switch (scenario) {
                case 0, 1, 2, 4, 11, 14 -> finish(condition, "verified=" + condition);
                case 3 -> {
                    if (ticks == 3) {
                        condition = bot.stopAll() && activity.status() == ActivityStatus.CANCELLED
                                && bot.navigator().status() == NavigationStatus.CANCELLED
                                && bot.player().xxa == 0 && bot.player().zza == 0;
                    }
                    if (ticks == 6) finish(condition && bot.player().xxa == 0 && bot.player().zza == 0,
                            "activity=" + activity.status() + " navigation=" + bot.navigator().status());
                }
                case 5, 10 -> {
                    var future = submitted;
                    if (future != null && future.isDone()) finish(Boolean.TRUE.equals(future.join()), "server_thread_or_guard=" + future.join());
                }
                case 6 -> {
                    if (submitted.isDone()) {
                        try { submitted.join(); finish(false, "missing_exception"); }
                        catch (CompletionException expected) {
                            finish(expected.getCause() instanceof IllegalArgumentException
                                    && "library_expected".equals(expected.getCause().getMessage()), "cause=" + expected.getCause());
                        }
                    }
                }
                case 7 -> {
                    if (ticks >= 3) finish(condition && submitted.isCancelled() && !invoked.get(), "cancelled=" + condition + " invoked=" + invoked.get());
                }
                case 8 -> {
                    if (ticks <= 3 && !level.getBlockState(p(1, 1, 0)).is(Blocks.STONE)) { finish(false, "wait_did_not_hold"); return; }
                    if (activity.completion().isDone()) {
                        boolean clear = true;
                        for (int x = 1; x <= 3; x++) for (int y = 0; y <= 1; y++) clear &= level.getBlockState(p(x, y, 0)).isAir();
                        finish(activity.status() == ActivityStatus.SUCCESS && clear
                                        && bot.player().position().distanceTo(point(0)) <= .75,
                                activity.completion().getNow(null) + " cobblestone=" + countAll(Items.COBBLESTONE));
                    }
                }
                case 9 -> {
                    if (ticks == 12) { activityRoundTrip(); condition = true; }
                    if (activity.completion().isDone()) finish(condition && activity.status() == ActivityStatus.SUCCESS
                            && ticks >= 38 && ticks <= 46, "ticks=" + ticks + " result=" + activity.completion().getNow(null));
                }
                case 12, 13 -> {
                    int removed = excavatedBlocks();
                    int boundary = scenario == 12 ? 6 : 2;
                    if (!condition && activity.stepIndex() == 1 && removed == boundary
                            && bot.miner().task() != null && bot.miner().task().status() == MiningStatus.SUCCESS) {
                        activityRoundTrip();
                        condition = true;
                    }
                    if (activity.completion().isDone()) {
                        if (scenario == 12) finish(condition && activity.status() == ActivityStatus.SUCCESS && excavatedBlocks() == 6
                                        && bot.player().position().distanceTo(point(0)) <= .75,
                                "saved=" + condition + " removed=" + excavatedBlocks() + " result=" + activity.completion().getNow(null));
                        else finish(condition && activity.status() == ActivityStatus.FAILED && excavatedBlocks() == 3
                                        && activity.completion().getNow(null).reason().contains("block_budget"),
                                "saved=" + condition + " removed=" + excavatedBlocks() + " result=" + activity.completion().getNow(null));
                    }
                }
            }
            if (active == this && ticks > 1200) finish(false, "timeout");
        } catch (Exception error) { finish(false, "exception=" + error); }
    }

    private void finish(boolean ok, String detail) {
        if (!ok) failed++;
        say((ok ? "OK " : "BAD ") + NAMES[scenario] + " " + detail);
        next();
    }

    private int excavatedBlocks() {
        int removed = 0;
        for (int x = 1; x <= 3; x++) for (int y = 0; y <= 1; y++) {
            if (level.getBlockState(p(x, y, 0)).isAir()) removed++;
        }
        return removed;
    }
}
