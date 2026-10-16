package dev.navigationaitest;

import dev.navigationai.NavigationAiMod;
import dev.navigationai.api.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** A 48-block dead end hides the entrance to the 260-block detour from a local snapshot. */
final class RegionRegression {
    private static RegionRegression active;
    private final CommandSourceStack source;
    private BotHandle bot;
    private NavigationTask task;
    private int ticks;
    private boolean enteredDeadEnd;
    RegionRegression(CommandSourceStack source) { this.source=source; }
    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(
                Commands.literal("navregions").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(command -> start(command.getSource()))));
        ServerTickEvents.END_SERVER_TICK.register(server -> { if(active!=null) active.tick(); });
    }
    private static int start(CommandSourceStack source) {
        if(active!=null) return 0;
        NavigationAiMod.runtime(source.getServer()).service().bots().forEach(BotHandle::remove);
        active=new RegionRegression(source); active.setup(); return 1;
    }
    private BlockPos p(int x,int y,int z) { return new BlockPos(10000+x,200+y,10000+z); }
    private void setup() {
        var level=source.getLevel();
        for(int x=-3;x<=103;x++) for(int z=-83;z<=3;z++) for(int y=-1;y<=2;y++) {
            level.setBlock(p(x,y,z),Blocks.BEDROCK.defaultBlockState(),3);
        }
        for(int x=0;x<=48;x++) open(x,0);
        for(int z=-80;z<=0;z++) { open(0,z); open(100,z); }
        for(int x=0;x<=100;x++) open(x,-80);
        bot=NavigationAiMod.runtime(source.getServer()).service().create(BotCreateOptions.at("region_probe",level,new Vec3(10000.5,200,10000.5)));
        task=bot.navigator().goTo(new NavigationGoal.PositionGoal(new Vec3(10100.5,200,10000.5),.75),
                new NavigationPolicy(false,false,false,true,40000,512,8,100,3));
        say("START");
    }
    private void open(int x,int z) { for(int y=0;y<=1;y++) source.getLevel().setBlock(p(x,y,z),Blocks.AIR.defaultBlockState(),3); }
    private void say(String text) { source.sendSystemMessage(Component.literal("REGIONS "+text)); }
    private void tick() {
        ticks++;
        if(bot.player().getX()>=10028 && Math.abs(bot.player().getZ()-10000.5)<1) enteredDeadEnd=true;
        if(ticks%200==0) say("PROGRESS ticks="+ticks+" position="+bot.player().position()+" status="+task.status());
        if(task.completion().isDone()||ticks>6000) {
            int backtracks=-1;
            try { var field=task.getClass().getDeclaredField("graphBacktracks");field.setAccessible(true);backtracks=field.getInt(task); } catch(Exception ignored) { }
            boolean ok=task.status()==NavigationStatus.SUCCESS && enteredDeadEnd && backtracks>0 && task.diagnostics().damageTaken()==0;
            if(!ok) dumpGraph();
            say((ok?"PASS ":"FAIL ")+"ticks="+ticks+" backtracks="+backtracks+" enteredDeadEnd="+enteredDeadEnd+" result="+task.completion().getNow(null)+" context="+task.failureContext());
            bot.remove(); active=null;
        }
    }
    private void dumpGraph() {
        try {
            var graphField=task.getClass().getDeclaredField("routeGraph");graphField.setAccessible(true);
            Object graph=graphField.get(task);
            var edgesField=graph.getClass().getDeclaredField("edges");edgesField.setAccessible(true);
            var edges=(java.util.Map<?,?>)edgesField.get(graph);
            say("GRAPH position="+bot.player().position()+" edges="+edges.size());
            for(Object edge:edges.values()) {
                var from=edge.getClass().getDeclaredMethod("from");from.setAccessible(true);
                var to=edge.getClass().getDeclaredMethod("to");to.setAccessible(true);
                var revision=edge.getClass().getDeclaredMethod("revision");revision.setAccessible(true);
                Object stamp=revision.invoke(edge);var current=stamp.getClass().getDeclaredMethod("current");current.setAccessible(true);
                say("EDGE from="+from.invoke(edge)+" to="+to.invoke(edge)+" current="+current.invoke(stamp));
            }
        } catch(Exception error) { say("GRAPH error="+error); }
    }
}
