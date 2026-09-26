package dev.navigationai.runtime;

import dev.navigationai.NavigationAiMod;
import dev.navigationai.api.*;
import dev.navigationai.navigation.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
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

/** Reproductions from the current review. Run only in a disposable world. */
public final class NavigationFixRegression {
    private static NavigationFixRegression active;
    private final CommandSourceStack source;
    private final ServerLevel level;
    private final BotRuntime runtime;
    private final NavigationPolicy safe = new NavigationPolicy(false,false,false,false,40000,512,8,100,3);
    private final NavigationPolicy build = new NavigationPolicy(false,true,false,false,40000,512,16,100,3);
    private BotHandle bot, target;
    private NavigationTask navigation;
    private MiningTask mining;
    private int phase, ticks, approach, passed, failed;
    private double cancelY;

    private NavigationFixRegression(CommandSourceStack source) {
        this.source=source; level=source.getLevel(); runtime=NavigationAiMod.runtime(source.getServer());
    }
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(
                Commands.literal("navfixes").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(command -> start(command.getSource()))));
        ServerTickEvents.END_SERVER_TICK.register(server -> { if(active!=null) active.tick(); });
    }
    private static int start(CommandSourceStack source) {
        if(active!=null) return 0;
        NavigationAiMod.runtime(source.getServer()).service().bots().forEach(BotHandle::remove);
        active=new NavigationFixRegression(source); active.setup(); return 1;
    }
    private BlockPos p(int x,int y,int z) { return new BlockPos(4096+x,200+y,4096+z); }
    private Vec3 point(double x,int z) { return new Vec3(4096+x,200,4096+z+.5); }
    private void block(int x,int y,int z,net.minecraft.world.level.block.Block block) {
        level.setBlock(p(x,y,z),block.defaultBlockState(),3);
    }
    private BotHandle create(String name,double x,int z) {
        return runtime.create(BotCreateOptions.at(name,level,point(x,z)));
    }
    private void reset(BotHandle handle,double x,int z) {
        handle.navigator().cancel(); handle.player().setPos(point(x,z)); handle.player().setDeltaMovement(Vec3.ZERO);
    }
    private void report(String message) { source.sendSystemMessage(Component.literal("FIXES "+message)); }
    private void check(String name,boolean ok,Object details) {
        if(ok) passed++; else failed++;
        report((ok?"OK ":"BAD ")+name+" "+details);
    }
    private void setup() {
        for(int x=-3;x<=16;x++) for(int z=-3;z<=3;z++) {
            block(x,-1,z,Blocks.STONE);
            for(int y=0;y<=3;y++) block(x,y,z,Blocks.AIR);
        }
        for(int z : new int[]{40,80}) for(int x=-1;x<=13;x++) {
            block(x,-1,z,Blocks.AIR);
            block(x,0,z,Blocks.AIR); block(x,1,z,Blocks.AIR); block(x,2,z,Blocks.STONE);
            for(int y=0;y<=2;y++) { block(x,y,z-1,Blocks.BEDROCK); block(x,y,z+1,Blocks.BEDROCK); }
        }
        for(int x=-1;x<=8;x++) if(x!=2) block(x,-1,40,Blocks.STONE);
        block(0,-1,80,Blocks.STONE); block(11,-1,80,Blocks.STONE);
        bot=create("fix_actor",.5,0); target=create("fix_target",5.5,0);
        report("START");
    }
    private void tick() {
        try {
            ticks++;
            switch(phase) {
                case 0 -> {
                    if(ticks<10) return;
                    finiteInputs(); futureIsolation();
                    bot.player().getInventory().setItem(0,new ItemStack(Items.STONE,16));
                    // The probe arena is away from both players; load its
                    // chunk explicitly as test setup before capturing it.
                    level.getChunkAt(p(0,0,80));
                    GridWorld world=WorldSnapshot.capture(level,bot.player(),new GridPos(4096,200,4176),16,
                            false,false,false,true,()->true);
                    GridPath path=GridPathfinder.find(world,new GridPos(4096,200,4176),new GridPos(4107,200,4176),build);
                    check("virtual_support_chain",path.goalReached()&&path.transitions().stream()
                            .filter(edge -> edge.actions().contains(GridTransition.Action.PLACE_SUPPORT)).count()==10,
                            "path="+path+" start="+world.passable(new GridPos(4096,200,4176))
                            +" middle="+world.passable(new GridPos(4101,200,4176))+" materials="+world.maxPlacements());
                    next(1);
                }
                case 1 -> {
                    if(ticks<10) return;
                    double tolerance=new double[]{.75,1,1.5,.1}[approach];
                    navigation=bot.navigator().goTo(new NavigationGoal.PositionGoal(target.player().position(),tolerance),safe);
                    next(2);
                }
                case 2 -> {
                    if(!navigation.completion().isDone()&&ticks<500) return;
                    double tolerance=new double[]{.75,1,1.5,.1}[approach];
                    boolean expected=approach==3?navigation.status()==NavigationStatus.FAILED
                            :navigation.status()==NavigationStatus.SUCCESS
                            &&bot.player().position().distanceTo(target.player().position())<=tolerance;
                    check("occupied_goal_"+tolerance,expected,navigation.completion().getNow(null));
                    reset(bot,.5,0);
                    if(++approach<4) next(1);
                    else { target.remove(); target=null; reset(bot,.5,40); next(3); }
                }
                case 3 -> {
                    if(ticks<10) return;
                    navigation=bot.navigator().goTo(new NavigationGoal.PositionGoal(point(6.5,40),.2),build);
                    next(4);
                }
                case 4 -> {
                    if(bot.player().blockPosition().equals(p(2,0,40))&&bot.player().onGround()
                            &&level.getBlockState(p(2,-1,40)).is(Blocks.STONE)) {
                        cancelY=bot.player().getY(); navigation.cancel(); next(5);
                    } else if(ticks>600||navigation.completion().isDone()) {
                        check("cancel_bridge_setup",false,navigation.completion().getNow(null)); finish();
                    }
                }
                case 5 -> {
                    if(ticks<30) return;
                    check("cancel_keeps_occupied_support",Math.abs(bot.player().getY()-cancelY)<.01
                            &&level.getBlockState(p(2,-1,40)).is(Blocks.STONE)&&bot.navigator().pendingRollbackCount()==1,
                            bot.player().position());
                    target=create("fix_guest",2.5,40); reset(bot,.5,40); next(6);
                }
                case 6 -> {
                    if(ticks<10) return;
                    check("rollback_keeps_other_entity_safe",level.getBlockState(p(2,-1,40)).is(Blocks.STONE),target.player().position());
                    var saved=net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                            net.minecraft.util.ProblemReporter.DISCARDING,source.getServer().registryAccess());
                    ((BotHandleImpl)bot).navigatorImpl().saveState(saved);
                    int materialCount=bot.player().getInventory().getItem(0).getCount();
                    bot.remove(); bot=create("fix_restored",.5,40);
                    bot.player().getInventory().setItem(0,new ItemStack(Items.STONE,materialCount));
                    var input=net.minecraft.world.level.storage.TagValueInput.create(
                            net.minecraft.util.ProblemReporter.DISCARDING,source.getServer().registryAccess(),saved.buildResult());
                    check("inactive_pending_rollback_restored",!input.getBooleanOr("active",true)
                            &&((BotHandleImpl)bot).navigatorImpl().restoreState(input)
                            &&level.getBlockState(p(2,-1,40)).is(Blocks.STONE),"NBT round trip with occupied support");
                    reset(target,.5,40); next(7);
                }
                case 7 -> {
                    if(ticks<10) return;
                    check("rollback_refunds_after_release",level.getBlockState(p(2,-1,40)).isAir()
                            &&bot.player().getInventory().getItem(0).getCount()==16&&bot.navigator().pendingRollbackCount()==0,
                            bot.player().getInventory().getItem(0));
                    target.remove(); target=null; bot.remove(); bot=create("fix_long_bridge",.5,80);
                    bot.player().getInventory().setItem(0,new ItemStack(Items.STONE,16));
                    navigation=bot.navigator().goTo(new NavigationGoal.PositionGoal(point(11.5,80),.3),build); next(8);
                }
                case 8 -> {
                    if(!navigation.completion().isDone()&&ticks<900) return;
                    int supports=0;
                    for(int x=1;x<=10;x++) if(level.getBlockState(p(x,-1,80)).is(Blocks.STONE)) supports++;
                    check("build_ten_supports",navigation.status()==NavigationStatus.SUCCESS&&supports==10
                            &&bot.player().getInventory().getItem(0).getCount()==6,navigation.completion().getNow(null));
                    bot.remove();
                    for(int x=-1;x<=4;x++) { block(x,-1,120,Blocks.STONE); block(x,0,120,Blocks.AIR); block(x,1,120,Blocks.AIR); }
                    block(1,0,120,Blocks.STONE); bot=create("fix_miner",.5,120);
                    bot.player().getInventory().setItem(0,new ItemStack(Items.WOODEN_PICKAXE));
                    mining=bot.miner().breakBlock(p(1,0,120)); next(9);
                }
                case 9 -> {
                    if(ticks==5) block(1,0,120,Blocks.DIRT);
                    if(ticks<10||ticks<30&&!mining.completion().isDone()) return;
                    MiningResult result=mining.completion().getNow(null);
                    check("mining_replacement_preserved",result!=null&&result.status()==MiningStatus.FAILED
                            &&result.reason().equals("block_changed")&&level.getBlockState(p(1,0,120)).is(Blocks.DIRT),result);
                    bot.remove();
                    for(int x=-28;x<=40;x++) for(int z=152;z<=168;z++) {
                        block(x,-1,z,Blocks.STONE);
                        for(int y=0;y<=4;y++) block(x,y,z,Blocks.AIR);
                    }
                    for(int x=-19;x<=0;x++) for(int y=0;y<=3;y++) {
                        block(x,y,159,Blocks.BEDROCK); block(x,y,161,Blocks.BEDROCK);
                    }
                    for(int y=0;y<=3;y++) block(1,y,160,Blocks.BEDROCK);
                    bot=create("fix_retreat",.5,160); next(10);
                }
                case 10 -> {
                    if(ticks<10) return;
                    navigation=bot.navigator().goTo(new NavigationGoal.PositionGoal(point(32.5,160),.4),safe);
                    next(11);
                }
                case 11 -> {
                    if(!navigation.completion().isDone()&&ticks<1200) return;
                    check("distant_goal_requires_initial_retreat",navigation.status()==NavigationStatus.SUCCESS,
                            navigation.completion().getNow(null)+" pos="+bot.player().position());
                    finish();
                }
            }
        } catch(Throwable error) { check("unexpected_exception",false,error); finish(); }
    }
    private void next(int phase) { this.phase=phase; ticks=0; }
    private void finiteInputs() {
        boolean ok=true;
        for(float value : new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}) {
            bot.actions().setMovement(.2f,.3f,false,false,false); bot.actions().look(40,20);
            try { bot.actions().setMovement(value,1,true,true,true); ok=false; } catch(IllegalArgumentException expected) { }
            try { bot.actions().setMovement(1,value,true,true,true); ok=false; } catch(IllegalArgumentException expected) { }
            try { bot.actions().look(value,0); ok=false; } catch(IllegalArgumentException expected) { }
            try { bot.actions().look(0,value); ok=false; } catch(IllegalArgumentException expected) { }
            ok &= bot.player().xxa==.2f&&bot.player().zza==.3f&&bot.player().getYRot()==40&&bot.player().getXRot()==20;
        }
        bot.actions().stop(); check("finite_inputs_atomic",ok,"NaN and both infinities");
    }
    private void futureIsolation() {
        boolean ok=true;
        for(int probe=0;probe<4;probe++) {
            NavigationTask task=bot.navigator().goTo(new NavigationGoal.PositionGoal(point(10.5,0)),safe);
            var future=task.completion();
            switch(probe) {
                case 0 -> future.cancel(false);
                case 1 -> future.complete(NavigationResult.success(0,0));
                case 2 -> future.completeExceptionally(new IllegalStateException("observer"));
                case 3 -> future.orTimeout(0,TimeUnit.NANOSECONDS);
            }
            task.cancel(); task.cancel();
            ok &= task.status()==NavigationStatus.CANCELLED&&bot.navigator().goal().isEmpty()
                    &&task.completion().join().status()==NavigationStatus.CANCELLED&&bot.player().zza==0;
        }
        check("future_lifecycle_isolation",ok,"cancel, complete, exception, timeout");
    }
    private void finish() {
        if(bot!=null) bot.remove(); if(target!=null) target.remove();
        report((failed==0?"PASS":"FAIL")+" passed="+passed+" failed="+failed); active=null;
    }
}
