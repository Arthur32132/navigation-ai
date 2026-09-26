package dev.navigationaitest;

import dev.navigationai.NavigationAiMod;
import dev.navigationai.api.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;

/** Combination tests with latency, replanning, damage and unfinished-task measurements. Disposable world only. */
public final class TransitionRegression {
    private static TransitionRegression active;
    private static final String[] NAMES = {"door_then_consecutive_jumps", "slab_under_low_ceiling",
            "sprint_lost_after_door", "flowing_water", "knockback_during_jump", "landing_changed_in_flight",
            "future_obstacle_replan_in_motion", "follow_snapshot_reuse", "follow_arrival_during_gap_jump"};
    private final CommandSourceStack source;
    private final ServerLevel level;
    private BotHandle bot, target;
    private NavigationTask task;
    private int scenario = -1, ticks, failed, dangling, stage, maxPaused, paused;
    private boolean changed, observed;
    private Vec3 previous;

    private TransitionRegression(CommandSourceStack source) { this.source = source; level = source.getLevel(); }
    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(
                Commands.literal("navtransitions").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(command -> start(command.getSource()))));
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (active != null) active.tick(); });
    }
    private static int start(CommandSourceStack source) {
        if (active != null) return 0;
        NavigationAiMod.runtime(source.getServer()).service().bots().forEach(BotHandle::remove);
        active = new TransitionRegression(source); active.next(); return 1;
    }
    private BlockPos p(int x, int y, int z) { return new BlockPos(3072 + x, 200 + y, 3072 + z); }
    private void block(int x, int y, int z, net.minecraft.world.level.block.Block block) {
        level.setBlock(p(x,y,z),block.defaultBlockState(),3);
    }
    private BotHandle create(String name, double x) {
        return NavigationAiMod.runtime(source.getServer()).service().create(
                BotCreateOptions.at(name, level, new Vec3(3072 + x, 200, 3072.5)));
    }
    private void door(int x) {
        var state=Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,net.minecraft.core.Direction.EAST);
        level.setBlock(p(x,0,0),state,3);
        level.setBlock(p(x,1,0),state.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER),3);
    }
    private void corridor(int end) {
        for (int x=-1;x<=end;x++) for (int y=0;y<=4;y++) {
            block(x,y,-1,Blocks.BEDROCK); block(x,y,1,Blocks.BEDROCK);
        }
        for (int y=0;y<=4;y++) block(-1,y,0,Blocks.BEDROCK);
    }
    private void go(int x, boolean water) {
        task=bot.navigator().goTo(new NavigationGoal.PositionGoal(new Vec3(3072.5+x,200,3072.5),.45),
                new NavigationPolicy(false,false,water,false,40000,512,16,100,3));
    }
    private void next() {
        if (bot!=null) bot.remove(); if (target!=null) target.remove(); target=null;
        if (++scenario>=NAMES.length) {
            report((failed==0?"PASS":"FAIL")+" passed="+(NAMES.length-failed)+" failed="+failed+" dangling="+dangling);
            active=null; return;
        }
        ticks=0;changed=false;observed=false;stage=0;maxPaused=0;paused=0;
        for (int x=-8;x<=32;x++) for (int z=-8;z<=8;z++) {
            block(x,-4,z,Blocks.STONE);
            for (int y=-3;y<=6;y++) block(x,y,z,y==-1?Blocks.STONE:Blocks.AIR);
        }
        bot=create("transition_actor",.5); previous=bot.player().position();
        report("START "+NAMES[scenario]);
        switch(scenario) {
            case 0 -> {
                corridor(14);door(1);
                for(int x=3;x<=5;x++) block(x,-1,0,Blocks.AIR);
                for(int x=7;x<=9;x++) block(x,-1,0,Blocks.AIR);
                go(12,false);
            }
            case 1 -> {
                corridor(8);block(4,0,0,Blocks.STONE_SLAB);
                level.setBlock(p(4,1,0),Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF,Half.TOP),3);
                go(4,false);
            }
            case 2 -> {
                corridor(10);door(1);
                for(int x=3;x<=5;x++) block(x,-1,0,Blocks.AIR);
                go(8,false);
            }
            case 3 -> {
                corridor(14);
                for(int x=2;x<=8;x++) { block(x,-1,0,Blocks.WATER); block(x,0,0,Blocks.WATER); }
                block(2,1,0,Blocks.WATER);
                go(12,true);
            }
            case 4,5 -> {
                corridor(14);for(int x=2;x<=3;x++) block(x,-1,0,Blocks.AIR);
                go(10,false);
            }
            case 6 -> go(24,false);
            case 7 -> {
                target=create("trans_target",8.5);target.player().setNoGravity(true);
                task=bot.navigator().follow(new NavigationGoal.FollowGoal(target.player(),1.5,2),
                        new NavigationPolicy(false,false,false,false,40000,512,16,100,3));
            }
            case 8 -> {
                corridor(14);
                for(int x=3;x<=5;x++) block(x,-1,0,Blocks.AIR);
                target=create("trans_target",7.5);target.player().setNoGravity(true);
                task=bot.navigator().follow(new NavigationGoal.FollowGoal(target.player(),3,4),
                        new NavigationPolicy(false,false,false,false,40000,512,16,100,3));
            }
        }
    }
    private void tick() {
        ticks++;
        var player=bot.player(); double x=player.getX()-3072;
        if(scenario==2 && changed) player.getFoodData().setFoodLevel(4);
        if(task.diagnostics().ticksToFirstMovement()>=0) {
            if(player.position().distanceToSqr(previous)<.0001) paused++; else paused=0;
            maxPaused=Math.max(maxPaused,paused);
        }
        previous=player.position();
        if(scenario==2 && !changed && task.status()==NavigationStatus.RUNNING && x>1) {
            player.getFoodData().setFoodLevel(4); changed=true;
        }
        if(scenario==3 && player.isInWater()) observed=true;
        if((scenario==4 || scenario==5) && !changed && !player.onGround() && x>1.5 && x<4) {
            if(scenario==4) player.setDeltaMovement(player.getDeltaMovement().add(0,0,.08));
            else block(4,0,0,Blocks.STONE);
            changed=true;
        }
        if(scenario==6 && !changed && x>2) {
            for(int z=-1;z<=1;z++) { block(10,0,z,Blocks.STONE);block(10,1,z,Blocks.STONE); }
            changed=true;
        }
        if(scenario==7 && task.status()==NavigationStatus.RUNNING) {
            if(stage==0 && x>1.5) { target.player().setPos(3084.5,200,3072.5);stage++; }
            else if(stage==1 && x>9) { target.player().setPos(3088.5,200,3072.5);stage++; }
            else if(stage==2 && player.distanceToSqr(target.player())<3) { task.cancel();stage++; }
        }
        if(scenario==8 && task.status()==NavigationStatus.RUNNING) {
            if(!player.onGround() && x>3 && x<6 && player.distanceToSqr(target.player())<=9) observed=true;
            if(observed && player.onGround() && x>=6 && Math.abs(player.getY()-200)<.1
                    && player.distanceToSqr(target.player())<=9) { task.cancel();stage=1; }
        }
        if(task.completion().isDone()) {
            boolean ok=switch(scenario) {
                case 1 -> task.status()==NavigationStatus.FAILED && task.diagnostics().damageTaken()==0;
                case 2 -> changed && task.status()==NavigationStatus.FAILED && x<3 && task.diagnostics().damageTaken()==0;
                case 3 -> observed && task.status()==NavigationStatus.SUCCESS;
                case 4,5 -> changed && task.status()==NavigationStatus.SUCCESS && task.diagnostics().damageTaken()<=2;
                case 6 -> changed && task.status()==NavigationStatus.SUCCESS && maxPaused<25;
                case 7 -> stage==3 && task.diagnostics().reusedSnapshotCells()>0 && task.diagnostics().replans()>=2;
                case 8 -> observed && stage==1 && task.diagnostics().damageTaken()==0;
                default -> task.status()==NavigationStatus.SUCCESS && task.diagnostics().damageTaken()==0;
            };
            finish(ok,"result="+task.completion().join()+" diagnostics="+task.diagnostics()+" maxPaused="+maxPaused
                    +" changed="+changed+" pos="+player.position());
        } else if(ticks>650) {
            dangling++;finish(false,"timeout status="+task.status()+" diagnostics="+task.diagnostics()+" pos="+player.position());
        }
    }
    private void finish(boolean ok,String details) {
        if(!ok) failed++;
        report((ok?"OK ":"BAD ")+NAMES[scenario]+" "+details);next();
    }
    private void report(String text) { source.sendSystemMessage(Component.literal("TRANSITIONS "+text)); }
}
