package dev.navigationaitest;

import dev.navigationai.NavigationAiMod;
import dev.navigationai.api.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Physical tunnel/stair mining, environmental rejection, progress and controller ownership. */
final class ExcavationRegression {
    private static ExcavationRegression active;
    private static final String[] NAMES = {"tunnel_walk_mine_return", "staircase_walk_mine_return", "fluid_face_rejected",
            "falling_ceiling_rejected", "container_face_protected", "missing_tool_rejected", "block_budget_partial_progress",
            "manual_navigation_cancels_excavation", "zero_budget_clear_passage", "mining_cannot_reach_through_wall",
            "buried_vein_followed", "excavation_future_isolation"};
    private final CommandSourceStack source;
    private final ServerLevel level;
    private BotHandle bot;
    private ExcavationTask excavation;
    private MiningTask mining;
    private GatherTask gathering;
    private NavigationTask navigation;
    private int scenario = -1, ticks, failed;
    private boolean condition;

    ExcavationRegression(CommandSourceStack source) { this.source = source; level = source.getLevel(); }
    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(
                Commands.literal("navexcavation").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(command -> start(command.getSource()))));
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (active != null) active.tick(); });
    }
    private static int start(CommandSourceStack source) {
        if (active != null) return 0;
        NavigationAiMod.runtime(source.getServer()).service().bots().forEach(BotHandle::remove);
        active = new ExcavationRegression(source); active.next(); return 1;
    }
    private BlockPos p(int x, int y, int z) { return new BlockPos(12_000 + x, 200 + y, 12_000 + z); }
    private Vec3 point(int x) { return new Vec3(12_000 + x + .5, 200, 12_000.5); }
    private void say(String text) { source.sendSystemMessage(Component.literal("EXCAVATION " + text)); }
    private void next() {
        if (bot != null) bot.remove();
        if (++scenario == NAMES.length) {
            say((failed == 0 ? "PASS" : "FAIL") + " passed=" + (NAMES.length - failed) + " failed=" + failed);
            active = null; return;
        }
        ticks = 0; condition = false; excavation = null; mining = null; gathering = null; navigation = null;
        for (int x = -3; x <= 12; x++) for (int z = -4; z <= 4; z++) {
            for (int y = -8; y <= 4; y++) level.setBlock(p(x,y,z), (y < 0 ? Blocks.BEDROCK : Blocks.AIR).defaultBlockState(), 3);
        }
        bot = NavigationAiMod.runtime(source.getServer()).service().create(BotCreateOptions.at("exc_worker", level, point(0)));
        bot.player().getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
        var request = new ExcavationRequest(p(0,0,0), Direction.EAST, ExcavationMode.TUNNEL, 4);
        for (int x = 1; x <= 4; x++) for (int y = 0; y <= 1; y++) level.setBlock(p(x,y,0), Blocks.STONE.defaultBlockState(), 3);
        var policy = new ExcavationPolicy(100, 2400, 8, true, true);
        switch (scenario) {
            case 0, 11 -> excavation = bot.miner().excavate(request, policy);
            case 1 -> {
                for (int x = 1; x <= 8; x++) for (int z = -2; z <= 2; z++) for (int y = -7; y <= 3; y++) {
                    level.setBlock(p(x,y,z), Blocks.STONE.defaultBlockState(), 3);
                }
                excavation = bot.miner().excavate(new ExcavationRequest(p(0,0,0), Direction.EAST, ExcavationMode.STAIRCASE_DOWN, 4), policy);
            }
            case 2 -> { level.setBlock(p(1,0,1), Blocks.WATER.defaultBlockState(), 3); excavation = bot.miner().excavate(request, policy); }
            case 3 -> { level.setBlock(p(1,2,0), Blocks.SAND.defaultBlockState(), 3); excavation = bot.miner().excavate(request, policy); }
            case 4 -> { level.setBlock(p(1,0,0), Blocks.CHEST.defaultBlockState(), 3); excavation = bot.miner().excavate(request, policy); }
            case 5 -> { bot.player().getInventory().clearContent(); excavation = bot.miner().excavate(request, policy); }
            case 6 -> excavation = bot.miner().excavate(request, new ExcavationPolicy(3,2400,8,true,true));
            case 7 -> excavation = bot.miner().excavate(request, policy);
            case 8 -> {
                for (int x = 1; x <= 4; x++) for (int y = 0; y <= 1; y++) level.setBlock(p(x,y,0), Blocks.AIR.defaultBlockState(), 3);
                excavation = bot.miner().excavate(request, new ExcavationPolicy(0,2400,8,true,true));
            }
            case 9 -> mining = bot.miner().breakBlock(p(2,0,0));
            case 10 -> {
                for (int x = -1; x <= 9; x++) for (int z = -1; z <= 1; z++) for (int y = 0; y <= 2; y++) {
                    level.setBlock(p(x,y,z), Blocks.STONE.defaultBlockState(), 3);
                }
                for (int x = -1; x <= 8; x++) {
                    level.setBlock(p(x,1,0), Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(p(x,0,0), (x >= 2 && x <= 7 ? Blocks.IRON_ORE : Blocks.AIR).defaultBlockState(), 3);
                }
                gathering = bot.collector().gather(new GatherRequest(Blocks.IRON_ORE, Items.RAW_IRON, 6), new GatherPolicy(8,2400,3));
            }
        }
        say("START " + NAMES[scenario]);
    }

    private void tick() {
        try {
            ticks++;
            if (scenario == 7 && ticks == 12) {
                navigation = bot.navigator().goTo(new NavigationGoal.PositionGoal(point(-2), .4), NavigationPolicy.safeDefaults());
                condition = excavation.status() == ExcavationStatus.CANCELLED && bot.miner().status() != MiningStatus.RUNNING;
            }
            if (scenario == 11 && ticks == 2) {
                excavation.completion().complete(new ExcavationResult(ExcavationStatus.SUCCESS,"external",99,99,false,0));
            }
            if (scenario == 9 && mining.completion().isDone()) {
                var result = mining.completion().getNow(null);
                finish(mining.status() == MiningStatus.FAILED && "obstructed".equals(result.reason())
                        && level.getBlockState(p(2,0,0)).is(Blocks.STONE), result.toString()); return;
            }
            if (scenario == 10 && gathering.completion().isDone()) {
                var result = gathering.completion().getNow(null);
                finish(gathering.status() == GatherStatus.SUCCESS && result.collectedItems() == 6 && result.brokenBlocks() == 6, result.toString()); return;
            }
            if (scenario == 7 && navigation != null && navigation.completion().isDone()) {
                finish(condition && navigation.status() == NavigationStatus.SUCCESS, "ownership=" + condition); return;
            }
            if (excavation != null && excavation.completion().isDone() && scenario != 7) {
                var result = excavation.completion().getNow(null);
                boolean ok = switch (scenario) {
                    case 0, 11 -> result.status() == ExcavationStatus.SUCCESS && result.completedSegments() == 4
                            && result.brokenBlocks() == 8 && result.returnedToEntrance() && bot.player().position().distanceTo(point(0)) <= .4;
                    case 1 -> result.status() == ExcavationStatus.SUCCESS && result.completedSegments() == 4
                            && result.brokenBlocks() == 12 && result.returnedToEntrance();
                    case 2 -> rejected(result,"unsafe_fluid");
                    case 3 -> rejected(result,"unstable_ceiling");
                    case 4 -> rejected(result,"protected_block_entity");
                    case 5 -> rejected(result,"no_suitable_tool");
                    case 6 -> result.status() == ExcavationStatus.FAILED && result.reason().equals("block_budget_exhausted")
                            && result.brokenBlocks() == 3 && result.completedSegments() == 1 && result.returnedToEntrance();
                    case 8 -> result.status() == ExcavationStatus.SUCCESS && result.brokenBlocks() == 0 && result.completedSegments() == 4;
                    default -> false;
                };
                finish(ok,result.toString()); return;
            }
            if (ticks > 2500) finish(false,"timeout");
        } catch (Throwable error) { finish(false,error.toString()); }
    }
    private boolean rejected(ExcavationResult result, String reason) {
        return result.status() == ExcavationStatus.FAILED && result.reason().equals(reason) && result.brokenBlocks() == 0;
    }
    private void finish(boolean success, String detail) {
        if (!success) failed++;
        say((success ? "PASS " : "FAIL ") + NAMES[scenario] + " ticks=" + ticks + " " + detail);
        next();
    }
}
