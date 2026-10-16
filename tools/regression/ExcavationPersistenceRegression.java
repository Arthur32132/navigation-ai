package dev.navigationaitest;

import dev.navigationai.NavigationAiMod;
import dev.navigationai.api.*;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Requires a real server stop/start between prepare and verify, including the block budget. */
final class ExcavationPersistenceRegression {
    private static final String COMPLETE = "persist_exc_ok", CAPPED = "persist_exc_cap";
    private static CommandSourceStack source;
    private static boolean preparing;
    private static int ticks;

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(
                Commands.literal("navexcavatepersist").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("prepare").executes(command -> prepare(command.getSource())))
                        .then(Commands.literal("verify").executes(command -> { source = command.getSource(); preparing = false; ticks = 0; return 1; }))));
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (source != null) tick(); });
    }

    private static BlockPos p(int x, int y, int lane) { return new BlockPos(14_000+x, 200+y, 14_000+lane); }
    private static Vec3 entrance(int lane) { return Vec3.atBottomCenterOf(p(0,0,lane)); }

    private static int prepare(CommandSourceStack command) {
        var runtime = NavigationAiMod.runtime(command.getServer());
        runtime.service().bots().forEach(BotHandle::remove);
        var level = command.getLevel();
        for (int x = -3; x <= 16; x++) for (int z = -3; z <= 7; z++) {
            level.setBlock(p(x,-1,z),Blocks.BEDROCK.defaultBlockState(),3);
            for (int y = 0; y <= 3; y++) level.setBlock(p(x,y,z),Blocks.AIR.defaultBlockState(),3);
        }
        for (int lane : new int[]{0,4}) {
            for (int x = 1; x <= 12; x++) for (int y = 0; y <= 1; y++) level.setBlock(p(x,y,lane),Blocks.STONE.defaultBlockState(),3);
            BotHandle bot = runtime.service().create(BotCreateOptions.at(lane == 0 ? COMPLETE : CAPPED,level,entrance(lane)));
            bot.player().getInventory().setItem(0,new ItemStack(Items.DIAMOND_PICKAXE));
            var request = new ExcavationRequest(p(0,0,lane),Direction.EAST,ExcavationMode.TUNNEL,12);
            var policy = new ExcavationPolicy(lane == 0 ? 24 : 11,4_000,8,true,lane != 0);
            bot.activities().run(List.of(new BotActivity.Excavate(request,policy),new BotActivity.Wait(12),
                    new BotActivity.Navigate(new NavigationGoal.ReturnGoal(entrance(lane),.4),NavigationPolicy.safeDefaults())));
        }
        source = command; preparing = true; ticks = 0; return 1;
    }

    private static void tick() {
        ticks++;
        var service = NavigationAiMod.runtime(source.getServer()).service();
        var complete = service.find(COMPLETE);
        var capped = service.find(CAPPED);
        if (complete.isEmpty() || capped.isEmpty()) { if (ticks > 40) end(false,"bot_missing"); return; }
        var successBot = complete.get();
        var cappedBot = capped.get();
        if (preparing) {
            var successDig = successBot.miner().excavationTask();
            var cappedDig = cappedBot.miner().excavationTask();
            if (successDig != null && cappedDig != null && successDig.completedSegments() >= 2 && cappedDig.completedSegments() >= 2
                    && successDig.status() == ExcavationStatus.RUNNING && cappedDig.status() == ExcavationStatus.RUNNING) {
                say("READY successSegments=" + successDig.completedSegments() + " successBlocks=" + successDig.brokenBlocks()
                        + " cappedSegments=" + cappedDig.completedSegments() + " cappedBlocks=" + cappedDig.brokenBlocks());
                source = null;
            }
        } else {
            var successTask = successBot.activities().task();
            var cappedTask = cappedBot.activities().task();
            if (successTask != null && cappedTask != null && successTask.completion().isDone() && cappedTask.completion().isDone()) {
                int fullBlocks = removedBlocks(0), cappedBlocks = removedBlocks(4);
                boolean ok = successTask.status() == ActivityStatus.SUCCESS && fullBlocks == 24
                        && successBot.player().position().distanceTo(entrance(0)) <= .4
                        && cappedTask.status() == ActivityStatus.FAILED && cappedBlocks == 11
                        && cappedTask.completion().getNow(null).reason().equals("excavation_block_budget_exhausted")
                        && cappedBot.player().position().distanceTo(entrance(4)) <= .4;
                end(ok,"fullBlocks=" + fullBlocks + " cappedBlocks=" + cappedBlocks + " success=" + successTask.completion().getNow(null)
                        + " capped=" + cappedTask.completion().getNow(null));
            }
        }
        if (source != null && ticks > 4_500) end(false,"timeout full=" + successBot.activities().status() + " capped=" + cappedBot.activities().status());
    }

    private static int removedBlocks(int lane) {
        int count = 0;
        for (int x = 1; x <= 12; x++) for (int y = 0; y <= 1; y++) {
            if (source.getLevel().getBlockState(p(x,y,lane)).isAir()) count++;
        }
        return count;
    }
    private static void say(String message) { source.sendSystemMessage(Component.literal("EXCAVATE PERSIST " + message)); }
    private static void end(boolean ok,String detail) {
        say((ok?"PASS ":"FAIL ") + detail);
        var service = NavigationAiMod.runtime(source.getServer()).service();
        service.find(COMPLETE).ifPresent(BotHandle::remove);
        service.find(CAPPED).ifPresent(BotHandle::remove);
        source = null;
    }
}
