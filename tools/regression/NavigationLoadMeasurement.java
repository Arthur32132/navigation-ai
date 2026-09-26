package dev.navigationaitest;

import dev.navigationai.NavigationAiMod;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Measures one explicitly bounded run, including complete Minecraft ticks. */
final class NavigationLoadMeasurement {
    private static NavigationLoadMeasurement active;
    private final List<Long> fullTicks = new ArrayList<>(), runtimeTicks = new ArrayList<>();
    private final CommandSourceStack source;
    private final boolean loadRun;
    private boolean sawLoad;
    private int finishedTick=-1;
    private int previousTick;
    private NavigationLoadMeasurement(CommandSourceStack source,boolean loadRun) {
        this.source=source; this.loadRun=loadRun; previousTick=source.getServer().getTickCount();
    }
    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(
                Commands.literal("navmeasure").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("start").executes(command -> start(command.getSource(),false)))
                        .then(Commands.literal("load").executes(command -> start(command.getSource(),true)))
                        .then(Commands.literal("stop").executes(command -> stop(command.getSource())))));
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if(active==null) return;
            // Read ticks already finished before this callback. The current
            // tick's duration is written only after its simulation completes.
            int completed=server.getTickCount()-1;
            if(active.finishedTick>=0) completed=Math.min(completed,active.finishedTick);
            long[] history=server.getTickTimesNanos();
            for(int tick=Math.max(active.previousTick+1,completed-history.length+1);tick<=completed;tick++) {
                active.fullTicks.add(history[Math.floorMod(tick,history.length)]);
            }
            active.previousTick=Math.max(active.previousTick,completed);
            if(active.finishedTick>=0&&completed>=active.finishedTick) stop(active.source);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if(active==null||active.finishedTick>=0) return;
            var runtime=NavigationAiMod.runtime(server);
            active.runtimeTicks.add(runtime.metrics().lastTickNanos());
            if(active.loadRun) {
                var bots=runtime.service().bots().stream().filter(bot -> bot.name().startsWith("load")).toList();
                if(!bots.isEmpty()) {
                    active.sawLoad=true;
                    boolean finished=bots.stream().noneMatch(bot -> bot.navigator().status()==dev.navigationai.api.NavigationStatus.COMPUTING
                            ||bot.navigator().status()==dev.navigationai.api.NavigationStatus.RUNNING);
                    if(finished) active.finishedTick=server.getTickCount();
                } else if(active.sawLoad) active.finishedTick=server.getTickCount();
            }
        });
    }
    private static int start(CommandSourceStack source,boolean loadRun) {
        if(active!=null) return 0;
        active=new NavigationLoadMeasurement(source,loadRun);
        source.sendSystemMessage(Component.literal("MEASURE START: per-run full server ticks and BotRuntime ticks"));
        return 1;
    }
    private static int stop(CommandSourceStack source) {
        if(active==null) return 0;
        active.source.sendSystemMessage(Component.literal("MEASURE DONE full="+summary(active.fullTicks)
                +" runtime="+summary(active.runtimeTicks)));
        active=null; return 1;
    }
    private static String summary(List<Long> samples) {
        if(samples.isEmpty()) return "no_samples";
        List<Long> sorted=samples.stream().sorted(Comparator.naturalOrder()).toList();
        double average=samples.stream().mapToLong(Long::longValue).average().orElse(0)/1_000_000.0;
        return "{ticks="+samples.size()+", avgMs="+average+", p95Ms="
                +sorted.get((int)Math.ceil(sorted.size()*.95)-1)/1_000_000.0+", maxMs="
                +sorted.getLast()/1_000_000.0+"}";
    }
}
