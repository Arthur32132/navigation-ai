package dev.navigationaitest;

import dev.navigationai.NavigationAiMod;
import dev.navigationai.api.*;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Controller ownership, interrupted work and early resource choice in a real server. */
final class ActivityRegression {
    private static ActivityRegression active;
    private static final String[] NAMES = {"nearby_resource_radius32", "symmetric_controller_ownership",
            "gather_combat_resume_return", "combat_timeout_cleanup", "manual_override_paused_activity",
            "activity_future_isolation", "cancel_activity_children", "cross_controller_reentrant_callback",
            "navigate_combat_resume", "two_interruptions_preserve_gather_count", "changed_mining_target_on_resume",
            "completion_callback_new_navigation", "activity_save_restore_progress", "paused_activity_save_restore",
            "resource_support_protection", "mining_cancel_callback_new_navigation", "floor_resource_safe_approach"};
    private final CommandSourceStack source;
    private final ServerLevel level;
    private BotHandle bot, target;
    private ActivityTask activity;
    private GatherTask gathering;
    private NavigationTask navigation;
    private int scenario = -1, ticks, failed, interruptions;
    private boolean condition;
    private final NavigationPolicy safe = new NavigationPolicy(false, false, false, false, 20000, 512, 16, 100, 3);

    private ActivityRegression(CommandSourceStack source) { this.source = source; level = source.getLevel(); }
    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(
                Commands.literal("navactivities").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(command -> start(command.getSource()))));
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (active != null) active.tick(); });
    }
    private static int start(CommandSourceStack source) {
        if (active != null) return 0;
        NavigationAiMod.runtime(source.getServer()).service().bots().forEach(BotHandle::remove);
        active = new ActivityRegression(source); active.next(); return 1;
    }
    private BlockPos p(int x, int y, int z) { return new BlockPos(6000 + x, 200 + y, 6000 + z); }
    private Vec3 point(int x) { return new Vec3(6000 + x + .5, 200, 6000.5); }
    private void say(String message) { source.sendSystemMessage(Component.literal("ACTIVITIES " + message)); }
    private int count() {
        return bot.player().getInventory().getNonEquipmentItems().stream()
                .filter(stack -> stack.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum();
    }
    private void next() {
        if (bot != null) bot.remove(); if (target != null) target.remove();
        if (++scenario == NAMES.length) { say((failed == 0 ? "PASS" : "FAIL") + " passed=" + (NAMES.length - failed) + " failed=" + failed); active = null; return; }
        ticks = 0; condition = false; interruptions = 0; activity = null; gathering = null; navigation = null;
        for (int x = -3; x <= 22; x++) for (int z = -4; z <= 4; z++) {
            level.setBlock(p(x, -1, z), Blocks.BEDROCK.defaultBlockState(), 3);
            for (int y = 0; y <= 3; y++) level.setBlock(p(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        var service = NavigationAiMod.runtime(source.getServer()).service();
        bot = service.create(BotCreateOptions.at("act_worker", level, point(0)));
        target = service.create(BotCreateOptions.at("act_target", level, new Vec3(6000.5, 200, 6003.5)));
        bot.player().getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
        bot.player().getInventory().setItem(1, new ItemStack(Items.DIAMOND_SWORD));
        for (int x : new int[]{4, 7, 10, 13}) level.setBlock(p(x, 0, 0), Blocks.STONE.defaultBlockState(), 3);
        var gather = new BotActivity.Gather(new GatherRequest(Blocks.STONE, Items.COBBLESTONE, 3), new GatherPolicy(32, 3000, 4), safe);
        switch (scenario) {
            case 0 -> gathering = bot.collector().gather(new GatherRequest(Blocks.STONE, Items.COBBLESTONE, 1), new GatherPolicy(32, 600, 4));
            case 1 -> ownership();
            case 2, 9, 12 -> activity = bot.activities().run(List.of(gather, new BotActivity.Navigate(new NavigationGoal.ReturnGoal(point(0), .75), safe)));
            case 3, 4, 5, 6, 8, 11, 13 -> activity = bot.activities().run(List.of(new BotActivity.Navigate(new NavigationGoal.PositionGoal(point(20), .75), safe)));
            case 7 -> crossReentry();
            case 10 -> activity = bot.activities().run(List.of(new BotActivity.Mine(p(4, 0, 0))));
            case 14 -> {
                level.setBlock(p(0, -1, 0), Blocks.STONE.defaultBlockState(), 3);
                gathering = bot.collector().gather(new GatherRequest(Blocks.STONE, Items.COBBLESTONE, 1), new GatherPolicy(2, 300, 3));
            }
            case 15 -> {
                MiningTask old = bot.miner().breakBlock(p(4, 0, 0));
                NavigationTask[] winner = new NavigationTask[1];
                old.completion().thenRun(() -> winner[0] = bot.navigator().goTo(new NavigationGoal.PositionGoal(point(20)), safe));
                MiningTask superseded = bot.miner().breakBlock(p(7, 0, 0));
                condition = winner[0] != null && (winner[0].status() == NavigationStatus.COMPUTING || winner[0].status() == NavigationStatus.RUNNING)
                        && bot.navigator().status() == winner[0].status() && superseded.status() == MiningStatus.CANCELLED;
            }
            case 16 -> {
                for(int x : new int[]{4,7,10,13}) level.setBlock(p(x,0,0),Blocks.AIR.defaultBlockState(),3);
                level.setBlock(p(2,-1,0),Blocks.STONE.defaultBlockState(),3);
                gathering=bot.collector().gather(new GatherRequest(Blocks.STONE,Items.COBBLESTONE,1),new GatherPolicy(4,400,3),safe);
            }
        }
        say("START " + NAMES[scenario]);
    }
    private void ownership() {
        NavigationTask nav = bot.navigator().goTo(new NavigationGoal.PositionGoal(point(20)), safe);
        GatherTask gather = bot.collector().gather(new GatherRequest(Blocks.STONE, Items.COBBLESTONE, 1), new GatherPolicy(8, 300, 3));
        boolean ok = nav.status() == NavigationStatus.CANCELLED;
        CombatTask combat = bot.combat().fight(target.player(), CombatPolicy.expert());
        ok &= gather.status() == GatherStatus.CANCELLED;
        nav = bot.navigator().goTo(new NavigationGoal.PositionGoal(point(20)), safe);
        ok &= combat.status() == CombatStatus.CANCELLED;
        MiningTask mine = bot.miner().breakBlock(p(4, 0, 0));
        ok &= nav.status() == NavigationStatus.CANCELLED;
        combat = bot.combat().fight(target.player(), CombatPolicy.expert());
        ok &= mine.status() == MiningStatus.CANCELLED;
        gather = bot.collector().gather(new GatherRequest(Blocks.STONE, Items.COBBLESTONE, 1), new GatherPolicy(8, 300, 3));
        condition = ok && combat.status() == CombatStatus.CANCELLED && gather.status() == GatherStatus.RUNNING;
    }
    private void crossReentry() {
        GatherTask old = bot.collector().gather(new GatherRequest(Blocks.STONE, Items.COBBLESTONE, 1), new GatherPolicy(8, 300, 3));
        CombatTask[] winner = new CombatTask[1];
        old.completion().thenRun(() -> winner[0] = bot.combat().fight(target.player(), CombatPolicy.expert()));
        NavigationTask superseded = bot.navigator().goTo(new NavigationGoal.PositionGoal(point(20)), safe);
        condition = winner[0] != null && winner[0].status() == CombatStatus.RUNNING
                && superseded.status() == NavigationStatus.CANCELLED && bot.combat().task() == winner[0];
    }
    private void interrupt(int timeout) {
        target.player().setPos(bot.player().position().add(2, 0, 0));
        target.player().setHealth(1);
        bot.activities().interruptForCombat(target.player(), CombatPolicy.expert().swordOnly(), timeout);
        interruptions++;
    }
    private void roundTrip() throws Exception {
        Object controller = bot.activities();
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, source.getServer().registryAccess());
        var shutdown = controller.getClass().getDeclaredMethod("shutdown"); shutdown.setAccessible(true); shutdown.invoke(controller);
        var save = controller.getClass().getDeclaredMethod("saveState", net.minecraft.world.level.storage.ValueOutput.class); save.setAccessible(true); save.invoke(controller, output);
        var input = net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, source.getServer().registryAccess(), output.buildResult());
        bot.activities().cancel();
        var restore = controller.getClass().getDeclaredMethod("restoreState", net.minecraft.world.level.storage.ValueInput.class); restore.setAccessible(true); restore.invoke(controller, input);
        activity = bot.activities().task();
    }
    private void tick() {
        try {
            ticks++;
            switch (scenario) {
                case 0 -> {
                    if (gathering.phase() != GatherPhase.SEARCHING && ticks < 10) condition = true;
                    if (gathering.completion().isDone()) finish(condition && gathering.status() == GatherStatus.SUCCESS && ticks < 200, "ticks=" + ticks + " count=" + count() + " result=" + gathering.completion().getNow(null));
                }
                case 1, 7, 15 -> finish(condition, "ownership=" + condition);
                case 2, 9 -> {
                    int required = scenario == 2 ? 1 : 2;
                    if (interruptions < required && activity.status() == ActivityStatus.RUNNING && activity.stepIndex() == 0 && count() > interruptions) {
                        // Revive the same test player after each successful interruption.
                        if (target.player().isDeadOrDying()) { target.remove(); target = NavigationAiMod.runtime(source.getServer()).service().create(BotCreateOptions.at("act_target", level, bot.player().position().add(2, 0, 0))); }
                        interrupt(200);
                    }
                    if (activity.completion().isDone()) finish(activity.status() == ActivityStatus.SUCCESS && interruptions == required && count() == 3
                            && bot.player().position().distanceTo(point(0)) <= .75, activity.completion().getNow(null) + " count=" + count());
                }
                case 3 -> {
                    if (ticks == 3) { target.player().setInvulnerable(true); interrupt(5); }
                    if (activity.completion().isDone()) finish(activity.status() == ActivityStatus.FAILED
                            && activity.completion().getNow(null).reason().equals("combat_timeout") && bot.combat().status() != CombatStatus.RUNNING,
                            activity.completion().getNow(null).toString());
                }
                case 4 -> {
                    if (ticks == 3) { interrupt(200); navigation = bot.navigator().goTo(new NavigationGoal.PositionGoal(point(0), .75), safe); condition = activity.status() == ActivityStatus.CANCELLED && bot.combat().status() == CombatStatus.CANCELLED; }
                    if (navigation != null && navigation.completion().isDone()) finish(condition && navigation.status() == NavigationStatus.SUCCESS, "override=" + condition);
                }
                case 5 -> {
                    if (ticks == 2) activity.completion().complete(new ActivityResult(ActivityStatus.SUCCESS, "external", 1, 0, 0));
                    if (activity.completion().isDone()) finish(activity.status() == ActivityStatus.SUCCESS && activity.completion().getNow(null).reason().equals("completed"), "ticks=" + ticks);
                }
                case 6 -> { if (ticks == 10) { activity.cancel(); finish(activity.status() == ActivityStatus.CANCELLED
                        && bot.navigator().status() == NavigationStatus.CANCELLED && bot.player().zza == 0, "cancelled"); } }
                case 8 -> {
                    if (ticks == 15) interrupt(200);
                    if (activity.completion().isDone()) finish(activity.status() == ActivityStatus.SUCCESS && interruptions == 1
                            && bot.player().position().distanceTo(point(20)) <= .75, activity.completion().getNow(null).toString());
                }
                case 10 -> {
                    if (ticks == 1) { interrupt(200); level.setBlock(p(4, 0, 0), Blocks.DIRT.defaultBlockState(), 3); }
                    if (activity.completion().isDone()) finish(activity.status() == ActivityStatus.FAILED
                            && activity.completion().getNow(null).reason().equals("mining_block_changed") && level.getBlockState(p(4, 0, 0)).is(Blocks.DIRT), activity.completion().getNow(null).toString());
                }
                case 11 -> {
                    if (ticks == 1) activity.completion().thenRun(() -> navigation = bot.navigator().goTo(new NavigationGoal.PositionGoal(point(0), .75), safe));
                    if (navigation != null && navigation.completion().isDone()) finish(navigation.status() == NavigationStatus.SUCCESS && activity.status() == ActivityStatus.SUCCESS, "callback_navigation=" + navigation.status());
                }
                case 12 -> {
                    if (!condition && count() >= 1 && activity.stepIndex() == 0) { condition = true; roundTrip(); }
                    if (activity.completion().isDone()) finish(condition && activity.status() == ActivityStatus.SUCCESS && count() == 3
                            && bot.player().position().distanceTo(point(0)) <= .75, activity.completion().getNow(null) + " count=" + count());
                }
                case 13 -> {
                    if (ticks == 15) { interrupt(200); roundTrip(); condition = activity.status() == ActivityStatus.PAUSED; }
                    if (activity.completion().isDone()) finish(condition && activity.status() == ActivityStatus.SUCCESS && activity.completion().getNow(null).interruptions() == 1, activity.completion().getNow(null).toString());
                }
                case 14 -> {
                    if (gathering.completion().isDone()) finish(gathering.status() == GatherStatus.FAILED && level.getBlockState(p(0, -1, 0)).is(Blocks.STONE)
                            && bot.player().getY() >= 200 && bot.player().getHealth() == 20, gathering.completion().getNow(null).toString());
                }
                case 16 -> {
                    if(bot.player().getY()<199.99 || bot.player().getHealth()<20) { finish(false,"unsafe_floor_approach"); return; }
                    if(gathering.completion().isDone()) finish(gathering.status()==GatherStatus.SUCCESS
                            && gathering.collectedItems()==1 && level.getBlockState(p(2,-1,0)).isAir(),gathering.completion().getNow(null).toString());
                }
            }
            if (active == this && ticks > 3500) finish(false, "timeout status=" + (activity == null ? gathering == null ? "none" : gathering.status() : activity.status()));
        } catch (Exception error) { finish(false, error.toString()); }
    }
    private void finish(boolean ok, String detail) {
        if (!ok) {
            failed++;
            if (bot != null && target != null) detail += " combat="+bot.combat().diagnostics()
                    +" actorPos="+bot.player().position()+" targetPos="+target.player().position()
                    +" targetHealth="+target.player().getHealth()+" targetVelocity="+target.player().getDeltaMovement()
                    +" actorGround="+bot.player().onGround()+" targetGround="+target.player().onGround()
                    +" view="+bot.player().getYRot()+","+bot.player().getXRot()
                    +" hand="+bot.player().getMainHandItem();
        }
        say((ok ? "OK " : "BAD ") + NAMES[scenario] + " " + detail);
        next();
    }
}
