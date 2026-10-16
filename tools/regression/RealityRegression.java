package dev.navigationaitest;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.navigationai.NavigationAiMod;
import dev.navigationai.api.*;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Real terrain journey and repeated mixed work. Only for isolated test worlds. */
final class RealityRegression {
    private static RealityRegression active;
    private final CommandSourceStack source;
    private final ServerLevel level;
    private final boolean mixed;
    private final int distance;
    private final List<BotHandle> bots = new ArrayList<>();
    private final List<NavigationTask> paths = new ArrayList<>();
    private final List<ActivityTask> work = new ArrayList<>();
    private final List<Long> fullTicks = new ArrayList<>();
    private final List<Long> runtimeTicks = new ArrayList<>();
    private final List<Vec3> previous = new ArrayList<>();
    private record JourneySample(int tick, Vec3 position, float health) { }
    private final List<JourneySample> journeyTrace = new ArrayList<>();
    private Vec3 origin, destination;
    private double travelled, horizontalTravelled;
    private int ticks, completed, round, supportsPlaced;
    private long startNanos;
    private int previousCompletedTick;
    private final NavigationPolicy policy = new NavigationPolicy(true, true, true, true, 40000, 2048, 32, 100, 3);

    private RealityRegression(CommandSourceStack source, boolean mixed, int distance) {
        this.source = source; this.level = source.getLevel(); this.mixed = mixed; this.distance = distance;
    }
    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(
                Commands.literal("navreality").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("journey").then(Commands.argument("distance", IntegerArgumentType.integer(100, 10000))
                                .executes(command -> start(command.getSource(), false, IntegerArgumentType.getInteger(command, "distance")))))
                        .then(Commands.literal("mixed").executes(command -> start(command.getSource(), true, 0)))
                        .then(Commands.literal("stop").executes(command -> { if (active == null) return 0; active.finish(false, "cancelled"); return 1; }))));
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (active == null) return;
            int tick = server.getTickCount() - 1;
            long[] history = server.getTickTimesNanos();
            for (int i = Math.max(active.previousCompletedTick + 1, tick - history.length + 1); i <= tick; i++) {
                active.fullTicks.add(history[Math.floorMod(i, history.length)]);
            }
            active.previousCompletedTick = tick;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (active != null) active.tick(); });
    }
    private static int start(CommandSourceStack source, boolean mixed, int distance) {
        if (active != null) return 0;
        if (source.getServer().getWorldData().getDifficulty() != Difficulty.PEACEFUL) {
            source.sendFailure(Component.literal("navreality requires difficulty peaceful")); return 0;
        }
        NavigationAiMod.runtime(source.getServer()).service().bots().forEach(BotHandle::remove);
        RealityRegression next = new RealityRegression(source, mixed, distance);
        active = next;
        try { if (mixed) next.setupMixed(); else next.setupJourney(); }
        catch (Exception failure) { next.finish(false, "setup_exception:" + failure); return 0; }
        next.startNanos = System.nanoTime();
        next.previousCompletedTick = source.getServer().getTickCount();
        next.say("START mode=" + (mixed ? "mixed" : "journey") + " distance=" + distance + " difficulty=peaceful origin=" + next.origin + " destination=" + next.destination);
        return 1;
    }
    private BotHandle create(String name, Vec3 position) {
        BotHandle bot = NavigationAiMod.runtime(source.getServer()).service().create(BotCreateOptions.at(name, level, position));
        bot.player().getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
        bot.player().getInventory().setItem(1, new ItemStack(Items.DIAMOND_SHOVEL));
        bot.player().getInventory().setItem(2, new ItemStack(Items.DIAMOND_AXE));
        for (int slot = 3; slot <= 11; slot++) bot.player().getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        bots.add(bot); previous.add(position); return bot;
    }
    private Vec3 surface(int x, int z) {
        // Only the two endpoint chunks are prepared. Intermediate terrain is generated by navigation tickets.
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        return new Vec3(x + .5, y, z + .5);
    }
    private void setupJourney() {
        origin = surface(0, 0); destination = surface(distance, 0);
        BotHandle bot = create("real_journey", origin);
        journeyTrace.add(new JourneySample(0,origin,bot.player().getHealth()));
        paths.add(bot.navigator().goTo(new NavigationGoal.PositionGoal(destination, 1.5), policy));
    }
    private BlockPos p(int x, int y, int z) { return new BlockPos(8000 + x, 230 + y, 8000 + z); }
    private void block(int x, int y, int z, net.minecraft.world.level.block.Block block) { level.setBlock(p(x,y,z), block.defaultBlockState(), 3); }
    private Vec3 point(int x, int z) { return new Vec3(8000 + x + .5, 230, 8000 + z + .5); }
    private void setupMixed() {
        origin = point(0, 0); destination = point(80, 0);
        for (int x = -3; x <= 85; x++) for (int z = -4; z <= 28; z++) {
            block(x, -1, z, Blocks.STONE);
            for (int y = 0; y <= 3; y++) block(x, y, z, Blocks.AIR);
        }
        for (int i = 0; i < 12; i++) {
            int x = i < 6 && i % 2 == 1 ? 80 : 0;
            int z = i < 6 ? i / 2 * 4 : i * 2;
            create("mixed" + i, point(x, z));
        }
        setupRound();
    }
    private void setupRound() {
        paths.clear(); work.clear();
        NavigationPolicy walking = new NavigationPolicy(false,false,false,true,20000,512,16,100,3);
        NavigationPolicy building = new NavigationPolicy(false,true,false,true,20000,512,16,100,3);
        for (int i = 0; i < bots.size(); i++) {
            BotHandle bot = bots.get(i);
            int z = i < 6 ? i / 2 * 4 : i * 2;
            if (i < 6) {
                int x = (i % 2 == 0) == (round % 2 == 0) ? 80 : 0;
                // Opposing bots share a four-block-wide corridor and have distinct arrival cells.
                paths.add(bot.navigator().goTo(new NavigationGoal.PositionGoal(point(x, z + (i % 2)), 1.5), walking));
            } else if (i < 9) {
                for (int x : new int[]{8, 14, 20}) block(x, 0, z, Blocks.GOLD_ORE);
                work.add(bot.activities().run(List.of(new BotActivity.Gather(new GatherRequest(Blocks.GOLD_ORE, Items.RAW_GOLD, 3),
                                new GatherPolicy(32, 4000, 4), walking),
                        new BotActivity.Navigate(new NavigationGoal.ReturnGoal(point(0, z), 1), walking))));
            } else {
                int end = round % 2 == 0 ? 30 : 0;
                // A low ceiling disables jumping so a missing support has to be placed.
                for (int x = 2; x < 29; x++) { block(x, -1, z, Blocks.AIR); block(x, 2, z, Blocks.STONE); }
                // Seal the lane across the entire platform: leaving around a
                // short wall would pass the goal without exercising placement.
                for (int x = -3; x <= 85; x++) for (int y = 0; y <= 3; y++) {
                    block(x, y, z-1, Blocks.BEDROCK); block(x, y, z+1, Blocks.BEDROCK);
                }
                for(int x : new int[]{-3,85}) for(int y=0;y<=3;y++) block(x,y,z,Blocks.BEDROCK);
                for(int slot=3;slot<=11;slot++) bot.player().getInventory().setItem(slot,ItemStack.EMPTY);
                bot.player().getInventory().setItem(3,new ItemStack(Items.COBBLESTONE,32));
                paths.add(bot.navigator().goTo(new NavigationGoal.PositionGoal(point(end, z), 1), building));
            }
        }
    }
    private void say(String message) { source.sendSystemMessage(Component.literal("REALITY " + message)); }
    private void tick() {
        try {
            ticks++;
            runtimeTicks.add(NavigationAiMod.runtime(source.getServer()).metrics().lastTickNanos());
            for (int i = 0; i < bots.size(); i++) {
                BotHandle bot = bots.get(i);
                Vec3 now = bot.player().position();
                travelled += now.distanceTo(previous.get(i));
                horizontalTravelled += Math.hypot(now.x-previous.get(i).x, now.z-previous.get(i).z);
                previous.set(i, now);
                if(!mixed && ticks%20==0) journeyTrace.add(new JourneySample(ticks,now,bot.player().getHealth()));
                if (bot.state() != BotState.ACTIVE) { finish(false, "bot_" + bot.state()); return; }
            }
            if (ticks % 200 == 0) say("PROGRESS ticks=" + ticks + " round=" + round + " position=" + bots.getFirst().player().position()
                    + " remaining=" + bots.getFirst().player().position().distanceTo(destination)
                    + " status=" + bots.getFirst().navigator().status() + " diagnostics=" + (paths.isEmpty() ? "none" : paths.getFirst().diagnostics()));
            for (NavigationTask path : paths) if (path.completion().isDone() && path.status() != NavigationStatus.SUCCESS) {
                finish(false, "navigation=" + path.completion().getNow(null) + " context=" + path.failureContext()); return;
            }
            for (ActivityTask task : work) if (task.completion().isDone() && task.status() != ActivityStatus.SUCCESS) {
                finish(false, "activity=" + task.completion().getNow(null)); return;
            }
            if (paths.stream().allMatch(path -> path.status() == NavigationStatus.SUCCESS)
                    && work.stream().allMatch(task -> task.status() == ActivityStatus.SUCCESS)) {
                completed += paths.size() + work.size();
                if (mixed) for(int i=9;i<12;i++) {
                    int laneSupports=0;
                    for(int x=2;x<29;x++) if(level.getBlockState(p(x,-1,i*2)).is(Blocks.COBBLESTONE)) laneSupports++;
                    if(laneSupports!=27) { finish(false,"bridge_supports="+laneSupports+" bot="+i); return; }
                    supportsPlaced+=laneSupports;
                }
                if (!mixed || ++round == 5) { finish(true, "reached_goal"); return; }
                say("ROUND completed=" + round + " tasks=" + completed); setupRound();
            }
            if (ticks > (mixed ? 18000 : 36000)) finish(false, "timeout");
        } catch (Exception failure) { finish(false, "exception:" + failure); }
    }
    private void finish(boolean ok, String reason) {
        if(!mixed && !bots.isEmpty()) {
            journeyTrace.add(new JourneySample(ticks,bots.getFirst().player().position(),bots.getFirst().player().getHealth()));
            var trace=source.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("navigation-journey.csv");
            try(var writer=java.nio.file.Files.newBufferedWriter(trace,java.nio.charset.StandardCharsets.UTF_8)) {
                writer.write("tick,x,y,z,health\n");
                for(var sample:journeyTrace) writer.write(sample.tick()+","+sample.position().x+","+sample.position().y+","+sample.position().z+","+sample.health()+"\n");
            } catch(java.io.IOException error) { ok=false;reason+=";trace_write="+error.getClass().getSimpleName(); }
        }
        for (BotHandle bot : bots) {
            say("BOT name=" + bot.name() + " position=" + bot.player().position() + " health=" + bot.player().getHealth()
                    + " gather=" + (bot.collector().task()==null ? "none" : bot.collector().task().completion().getNow(null)));
            bot.remove();
        }
        try {
            var runtime=NavigationAiMod.runtime(source.getServer());
            for(String field : new String[]{"navigationTickets","navigationTicketRefs","navigationTicketDemands","resourceReservations","resourceDrops"}) {
                var declared=runtime.getClass().getDeclaredField(field);declared.setAccessible(true);
                if(!((java.util.Map<?,?>)declared.get(runtime)).isEmpty()) { ok=false; reason+=";leaked_"+field; }
            }
        } catch(Exception error) { ok=false; reason+=";cleanup_check="+error; }
        say((ok ? "PASS" : "FAIL") + " mode=" + (mixed ? "mixed" : "journey") + " reason=" + reason
                + " completed=" + completed + " supports="+supportsPlaced+" ticks=" + ticks + " travelled=" + travelled + " horizontal="+horizontalTravelled+" seconds=" + (System.nanoTime() - startNanos) / 1e9
                + " full=" + summary(fullTicks) + " runtime=" + summary(runtimeTicks)
                + " metrics=" + NavigationAiMod.runtime(source.getServer()).metrics());
        active = null;
    }
    private static String summary(List<Long> samples) {
        if (samples.isEmpty()) return "no_samples";
        List<Long> sorted = samples.stream().sorted().toList();
        return "{ticks=" + samples.size() + ",avgMs=" + samples.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6
                + ",p95Ms=" + sorted.get((int)Math.ceil(sorted.size() * .95) - 1) / 1e6
                + ",p99Ms=" + sorted.get((int)Math.ceil(sorted.size() * .99) - 1) / 1e6
                + ",maxMs=" + sorted.getLast() / 1e6 + "}";
    }
}
