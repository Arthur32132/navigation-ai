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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Two commands with a real stop/start between them, driven by run-validation.py. */
final class ActivityPersistenceRegression {
    private static CommandSourceStack source;
    private static boolean preparing;
    private static int ticks;
    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(
                Commands.literal("navpersist").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("prepare").executes(command -> prepare(command.getSource())))
                        .then(Commands.literal("verify").executes(command -> { source = command.getSource(); preparing = false; ticks = 0; return 1; }))));
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (source != null) tick(); });
    }
    private static int prepare(CommandSourceStack command) {
        var runtime = NavigationAiMod.runtime(command.getServer());
        runtime.service().bots().forEach(BotHandle::remove);
        var level = command.getLevel();
        for (int x = -3; x <= 44; x++) for (int z = -3; z <= 3; z++) {
            level.setBlock(new BlockPos(7000+x,199,7000+z), Blocks.BEDROCK.defaultBlockState(),3);
            for (int y = 200; y <= 203; y++) level.setBlock(new BlockPos(7000+x,y,7000+z),Blocks.AIR.defaultBlockState(),3);
        }
        for (int x : new int[]{5,10,15,20,25,30,35,40}) level.setBlock(new BlockPos(7000+x,200,7000),Blocks.GOLD_ORE.defaultBlockState(),3);
        BotHandle bot = runtime.service().create(BotCreateOptions.at("persist_activity",level,new Vec3(7000.5,200,7000.5)));
        bot.player().getInventory().setItem(0,new ItemStack(Items.DIAMOND_PICKAXE));
        NavigationPolicy policy = new NavigationPolicy(false,false,false,true,40000,512,32,100,3);
        bot.activities().run(List.of(new BotActivity.Gather(new GatherRequest(Blocks.GOLD_ORE,Items.RAW_GOLD,5),new GatherPolicy(32,4000,4),policy),
                new BotActivity.Navigate(new NavigationGoal.ReturnGoal(new Vec3(7000.5,200,7000.5),.75),policy)));
        source = command; preparing = true; ticks = 0; return 1;
    }
    private static void say(String message) { source.sendSystemMessage(Component.literal("PERSIST " + message)); }
    private static void tick() {
        ticks++;
        var runtime = NavigationAiMod.runtime(source.getServer());
        var found = runtime.service().find("persist_activity");
        if (found.isEmpty()) { if (ticks > 40) end(false,"bot_missing"); return; }
        var bot = found.get();
        int count = bot.player().getInventory().getNonEquipmentItems().stream().filter(stack -> stack.is(Items.RAW_GOLD)).mapToInt(ItemStack::getCount).sum();
        if (preparing) {
            if (count >= 1 && bot.activities().status() == ActivityStatus.RUNNING) {
                say("READY count=" + count + " step=" + bot.activities().task().stepIndex()); source = null;
            }
        } else {
            var task = bot.activities().task();
            if (task != null && task.completion().isDone()) end(task.status() == ActivityStatus.SUCCESS && count == 5
                    && bot.player().position().distanceTo(new Vec3(7000.5,200,7000.5)) <= .75,
                    "count=" + count + " result=" + task.completion().getNow(null));
        }
        if (source != null && ticks > 4500) end(false,"timeout count=" + count + " activity=" + bot.activities().status());
    }
    private static void end(boolean ok,String detail) {
        say((ok?"PASS ":"FAIL ") + detail);
        NavigationAiMod.runtime(source.getServer()).service().find("persist_activity").ifPresent(BotHandle::remove);
        source = null;
    }
}
