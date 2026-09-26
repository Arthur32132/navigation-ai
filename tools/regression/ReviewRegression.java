package dev.navigationaitest;

import dev.navigationai.NavigationAiMod;
import dev.navigationai.api.*;
import dev.navigationai.navigation.GridPos;
import dev.navigationai.navigation.GridWorld;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;

/** Review regressions. Changes the arena and must only run in a disposable world. */
public final class ReviewRegression {
    private static ReviewRegression active;
    private static final String[] NAMES = {"slab_headroom", "block_goal_all_approaches", "moving_follow",
            "miner_reentrant_start", "collector_reentrant_start", "combat_reentrant_start",
            "final_approach_new_hazard", "unsafe_building_material", "insufficient_bridge_material",
            "request_metrics_across_segments", "incremental_resource_scan", "resource_reservation",
            "new_obstacle_inside_jump", "new_powder_snow_on_route", "fair_resource_scan_budget"};
    private final CommandSourceStack source;
    private final ServerLevel level;
    private final List<BotHandle> bots = new ArrayList<>();
    private BotHandle bot, target;
    private NavigationTask navigation;
    private GatherTask gathering;
    private final List<GatherTask> concurrentGathering = new ArrayList<>();
    private int scenario=-1, ticks, failed;
    private boolean condition, changed;
    private Vec3 anchor;
    private double maxDisplacement;
    private long started, beforeNodes;

    private ReviewRegression(CommandSourceStack source) { this.source=source; this.level=source.getLevel(); }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(
                Commands.literal("navreview").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(command -> start(command.getSource()))));
        ServerTickEvents.END_SERVER_TICK.register(server -> { if(active!=null) active.tick(); });
    }

    private static int start(CommandSourceStack source) {
        if(active!=null) return 0;
        NavigationAiMod.runtime(source.getServer()).service().bots().forEach(BotHandle::remove);
        active=new ReviewRegression(source);
        active.next();
        return 1;
    }

    private BlockPos p(int x,int y,int z) { return new BlockPos(1024+x,200+y,1024+z); }
    private void block(int x,int y,int z,net.minecraft.world.level.block.Block block) {
        level.setBlock(p(x,y,z),block.defaultBlockState(),3);
    }
    private BotHandle create(String name,double x,double z) {
        BotHandle created=NavigationAiMod.runtime(source.getServer()).service().create(
                BotCreateOptions.at(name,level,new Vec3(1024+x,200,1024+z)));
        bots.add(created);return created;
    }
    private NavigationPolicy policy(boolean placing) {
        return new NavigationPolicy(false,placing,false,false,40000,512,8,100,3);
    }
    private void report(String message) { source.sendSystemMessage(Component.literal("REVIEW "+message)); }
    private void next() {
        for(BotHandle handle:bots) handle.remove();
        bots.clear();
        concurrentGathering.clear();
        if(++scenario>=NAMES.length) {
            report((failed==0?"PASS":"FAIL")+" passed="+(NAMES.length-failed)+" failed="+failed);
            active=null;return;
        }
        ticks=0;condition=false;changed=false;maxDisplacement=0;navigation=null;gathering=null;
        for(int x=-20;x<=30;x++) for(int z=-20;z<=20;z++) {
            block(x,-1,z,Blocks.STONE);
            for(int y=0;y<=5;y++) block(x,y,z,Blocks.AIR);
        }
        bot=create("review_actor",scenario==6?0.15:0.5,0.5);
        report("START "+NAMES[scenario]);
        try {
            switch(scenario) {
                case 0 -> {
                    BlockPos cell=p(5,0,0);
                    level.setBlock(cell,Blocks.STONE_SLAB.defaultBlockState(),3);
                    level.setBlock(cell.above(),Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF,Half.TOP),3);
                    Method capture=Class.forName("dev.navigationai.runtime.WorldSnapshot").getDeclaredMethod("capture",
                            ServerLevel.class,ServerPlayer.class,GridPos.class,int.class,boolean.class,boolean.class,
                            boolean.class,boolean.class,BooleanSupplier.class);
                    capture.setAccessible(true);
                    GridPos grid=new GridPos(cell.getX(),cell.getY(),cell.getZ());
                    GridWorld snapshot=(GridWorld)capture.invoke(null,level,bot.player(),grid,2,false,false,false,false,
                            (BooleanSupplier)()->true);
                    condition=!snapshot.passable(grid);
                }
                case 1 -> {
                    block(4,0,0,Blocks.STONE);
                    for(int x=-8;x<=12;x++) for(int z=-8;z<=8;z++) block(x,3,z,Blocks.STONE);
                    navigation=bot.navigator().goTo(new NavigationGoal.BlockGoal(p(4,0,0)),policy(false));
                }
                case 2 -> {
                    target=create("review_target",23.5,0.5);
                    target.player().setNoGravity(true);
                    anchor=bot.player().position();
                    navigation=bot.navigator().follow(new NavigationGoal.FollowGoal(target.player(),2,4),NavigationPolicy.safeDefaults());
                }
                case 3 -> {
                    block(1,0,0,Blocks.DIRT);block(2,0,0,Blocks.DIRT);block(3,0,0,Blocks.DIRT);
                    MiningTask a=bot.miner().breakBlock(p(1,0,0));
                    MiningTask[] nested=new MiningTask[1];
                    a.completion().thenRun(()->nested[0]=bot.miner().breakBlock(p(2,0,0)));
                    MiningTask outer=bot.miner().breakBlock(p(3,0,0));
                    condition=nested[0]!=null && bot.miner().task()==nested[0]
                            && nested[0].status()==MiningStatus.RUNNING && outer.status()==MiningStatus.CANCELLED
                            && outer.completion().isDone();
                    if(nested[0]!=null) nested[0].cancel();
                }
                case 4 -> {
                    GatherRequest request=new GatherRequest(Blocks.DIRT,Items.DIRT,1);
                    GatherTask a=bot.collector().gather(request,new GatherPolicy(4,300,3));
                    GatherTask[] nested=new GatherTask[1];
                    a.completion().thenRun(()->nested[0]=bot.collector().gather(request,new GatherPolicy(4,300,3)));
                    GatherTask outer=bot.collector().gather(request,new GatherPolicy(4,300,3));
                    condition=nested[0]!=null && bot.collector().task()==nested[0]
                            && nested[0].status()==GatherStatus.RUNNING && outer.status()==GatherStatus.CANCELLED;
                    if(nested[0]!=null) nested[0].cancel();
                }
                case 5 -> {
                    CombatTask a=bot.combat().fightNearby(8,CombatPolicy.standard());
                    CombatTask[] nested=new CombatTask[1];
                    a.completion().thenRun(()->nested[0]=bot.combat().fightNearby(8,CombatPolicy.standard()));
                    CombatTask outer=bot.combat().fightNearby(8,CombatPolicy.standard());
                    condition=nested[0]!=null && bot.combat().task()==nested[0]
                            && nested[0].status()==CombatStatus.RUNNING && outer.status()==CombatStatus.CANCELLED;
                    if(nested[0]!=null) nested[0].cancel();
                }
                case 6 -> navigation=bot.navigator().goTo(
                        new NavigationGoal.PositionGoal(new Vec3(1024.95,200,1024.5),0.01),policy(false));
                case 7,8 -> {
                    int length=scenario==7?2:3;
                    for(int x=-1;x<=length+1;x++) {
                        block(x,2,0,Blocks.BEDROCK);
                        for(int y=0;y<=3;y++) { block(x,y,-1,Blocks.BEDROCK);block(x,y,1,Blocks.BEDROCK); }
                    }
                    for(int y=0;y<=3;y++) { block(-1,y,0,Blocks.BEDROCK);block(length+1,y,0,Blocks.BEDROCK); }
                    for(int x=1;x<length;x++) block(x,-1,0,Blocks.AIR);
                    bot.player().getInventory().setItem(0,new ItemStack(scenario==7?Items.MAGMA_BLOCK:Items.STONE,1));
                    navigation=bot.navigator().goTo(new NavigationGoal.PositionGoal(new Vec3(1024.5+length,200,1024.5)),policy(true));
                }
                case 9 -> {
                    started=System.nanoTime();
                    beforeNodes=NavigationAiMod.runtime(source.getServer()).metrics().pathNodesVisited();
                    navigation=bot.navigator().goTo(new NavigationGoal.MultiGoal(List.of(
                            new NavigationGoal.PositionGoal(new Vec3(1032.5,200,1024.5)),
                            new NavigationGoal.PositionGoal(new Vec3(1040.5,200,1024.5)))),policy(false));
                }
                case 10 -> gathering=bot.collector().gather(new GatherRequest(Blocks.DIRT,Items.DIRT,1),new GatherPolicy(32,2000,3));
                case 11 -> {
                    var runtime=NavigationAiMod.runtime(source.getServer());
                    Method reserve=runtime.getClass().getDeclaredMethod("reserveResource",ServerLevel.class,BlockPos.class,Object.class);
                    Method release=runtime.getClass().getDeclaredMethod("releaseResources",Object.class);
                    reserve.setAccessible(true);release.setAccessible(true);
                    Object first=new Object(),second=new Object();
                    condition=(boolean)reserve.invoke(runtime,level,p(1,0,0),first)
                            && !(boolean)reserve.invoke(runtime,level,p(1,0,0),second);
                    release.invoke(runtime,first);
                    condition &= (boolean)reserve.invoke(runtime,level,p(1,0,0),second);
                    release.invoke(runtime,second);
                }
                case 12 -> {
                    for(int x=-1;x<=4;x++) for(int y=0;y<=4;y++) {
                        block(x,y,-1,Blocks.BEDROCK);block(x,y,1,Blocks.BEDROCK);
                    }
                    for(int y=0;y<=4;y++) { block(-1,y,0,Blocks.BEDROCK);block(4,y,0,Blocks.BEDROCK); }
                    block(1,-1,0,Blocks.AIR);block(2,-1,0,Blocks.AIR);
                    navigation=bot.navigator().goTo(new NavigationGoal.PositionGoal(new Vec3(1027.5,200,1024.5)),policy(false));
                }
                case 13 -> navigation=bot.navigator().goTo(
                        new NavigationGoal.PositionGoal(new Vec3(1030.5,200,1024.5)),policy(false));
                case 14 -> {
                    for(int index=0;index<4;index++) {
                        BotHandle collector=index==0?bot:create("review_scan_"+index,0.5,index*3+0.5);
                        concurrentGathering.add(collector.collector().gather(
                                new GatherRequest(Blocks.STONE,Items.COBBLESTONE,1),new GatherPolicy(32,2000,3)));
                    }
                }
            }
        } catch(Exception error) { finish(false,error.toString()); }
    }

    private void tick() {
        try {
            ticks++;
            switch(scenario) {
                case 0,3,4,5,11 -> finish(condition,"contract="+condition);
                case 1 -> { if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS,
                        navigation.completion().join().toString()); }
                case 2 -> {
                    target.player().setPos(1037.5+10*Math.cos(ticks*0.05),200,1024.5+10*Math.sin(ticks*0.05));
                    target.player().setDeltaMovement(Vec3.ZERO);
                    maxDisplacement=Math.max(maxDisplacement,anchor.distanceTo(bot.player().position()));
                    if(ticks>=240) finish(maxDisplacement>8 && !navigation.completion().isDone(),
                            "movement="+maxDisplacement+" status="+navigation.status());
                }
                case 6 -> {
                    if(!changed && navigation.status()==NavigationStatus.RUNNING) {
                        changed=true;block(1,-1,0,Blocks.MAGMA_BLOCK);
                    }
                    if(navigation.completion().isDone()) finish(changed && navigation.status()!=NavigationStatus.SUCCESS
                            && bot.player().getHealth()==20 && bot.player().getX()<1024.8,
                            "status="+navigation.status()+" pos="+bot.player().position()+" health="+bot.player().getHealth());
                }
                case 7,8 -> {
                    if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.FAILED
                            && level.getBlockState(p(1,-1,0)).isAir() && bot.player().getInventory().getItem(0).getCount()==1,
                            navigation.completion().join().toString());
                }
                case 9 -> {
                    if(navigation.completion().isDone()) {
                        NavigationResult result=navigation.completion().join();
                        long elapsed=System.nanoTime()-started;
                        long nodes=NavigationAiMod.runtime(source.getServer()).metrics().pathNodesVisited()-beforeNodes;
                        finish(result.status()==NavigationStatus.SUCCESS && result.nodesVisited()==nodes
                                && result.durationNanos()>=elapsed-100_000_000L && result.durationNanos()<=elapsed,
                                "result="+result+" elapsed="+elapsed+" runtimeNodes="+nodes);
                    }
                }
                case 10 -> {
                    var field=gathering.getClass().getDeclaredField("scanIndex");field.setAccessible(true);
                    int scanned=field.getInt(gathering);
                    if(ticks==2) {
                        condition=scanned>0 && scanned<=1024 && gathering.phase()==GatherPhase.SEARCHING;
                        gathering.cancel();finish(condition,"scanned="+scanned+" phase="+gathering.phase());
                    }
                }
                case 12 -> {
                    if(!changed && navigation.status()==NavigationStatus.RUNNING) {
                        changed=true;block(1,0,0,Blocks.BEDROCK);block(1,1,0,Blocks.BEDROCK);
                    }
                    if(navigation.completion().isDone()) finish(changed && navigation.status()!=NavigationStatus.SUCCESS
                            && bot.player().getHealth()==20 && bot.player().getX()<1025.0,
                            "status="+navigation.status()+" pos="+bot.player().position());
                }
                case 13 -> {
                    if(!changed && navigation.status()==NavigationStatus.RUNNING) {
                        changed=true;block(3,0,0,Blocks.POWDER_SNOW);
                    }
                    if(bot.player().blockPosition().equals(p(3,0,0))) condition=true;
                    if(navigation.completion().isDone()) finish(changed && !condition
                            && navigation.status()==NavigationStatus.SUCCESS && bot.player().getHealth()==20,
                            "status="+navigation.status()+" enteredHazard="+condition+" health="+bot.player().getHealth());
                }
                case 14 -> {
                    if(ticks==20) {
                        int min=Integer.MAX_VALUE,max=0;
                        for(GatherTask scan:concurrentGathering) {
                            var field=scan.getClass().getDeclaredField("scanIndex");field.setAccessible(true);
                            int scanned=field.getInt(scan);min=Math.min(min,scanned);max=Math.max(max,scanned);
                        }
                        finish(min>0 && max-min<=2048,"minCells="+min+" maxCells="+max);
                    }
                }
            }
            if(active==this && ticks>600) finish(false,"timeout status="+(navigation==null?"none":navigation.status()));
        } catch(Exception error) { finish(false,error.toString()); }
    }

    private void finish(boolean ok,String detail) {
        if(!ok) failed++;
        report((ok?"OK ":"ERROR ")+NAMES[scenario]+": "+detail);
        next();
    }
}
