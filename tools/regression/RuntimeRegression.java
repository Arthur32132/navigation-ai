package dev.navigationaitest;

import dev.navigationai.NavigationAiMod;
import dev.navigationai.api.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/** Explicit, destructive-to-arena integration test. Run only in a disposable world. */
public final class RuntimeRegression {
    private static RuntimeRegression active;
    private final CommandSourceStack source;
    private final ServerLevel level;
    private BotHandle bot, target;
    private MiningTask mining;
    private GatherTask gathering;
    private int scenario = -1, ticks, passed, failed;
    private boolean replacementSeen;
    private static final String[] NAMES = {"mining_inventory_tool_restore", "mining_cancel_before_tick",
        "mining_no_tool_preserves_slot", "unbreakable_block", "gather_and_pickup", "full_inventory",
        "missing_resource", "gather_timeout_cancels_navigation", "gather_cancel",
        "best_sword_and_broken_weapon_replacement", "bow_inventory_arrows_damage", "combat_one_block_step",
        "unreachable_resource", "retry_other_side"};

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (active != null) active.tick();
        });
    }

    public static int start(CommandSourceStack source) {
        if (active != null) return 0;
        active = new RuntimeRegression(source);
        active.next();
        return 1;
    }

    private RuntimeRegression(CommandSourceStack source) {
        this.source = source;
        this.level = source.getLevel();
    }

    private BlockPos p(int x, int y, int z) { return new BlockPos(1024 + x, 200 + y, 1024 + z); }
    private void block(int x, int y, int z, net.minecraft.world.level.block.Block block) {
        level.setBlockAndUpdate(p(x,y,z), block.defaultBlockState());
    }
    private BotHandle create(String name, int x, int y) {
        return NavigationAiMod.runtime(source.getServer()).service().create(
            BotCreateOptions.at(name, level, new Vec3(1024.5+x, 200+y, 1024.5)));
    }
    private void cleanup() {
        if (bot != null) { bot.remove(); bot = null; }
        if (target != null) { target.remove(); target = null; }
        level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
            new net.minecraft.world.phys.AABB(1008,198,1008,1041,208,1041)).forEach(e -> e.discard());
    }
    private void next() {
        cleanup();
        if (++scenario == NAMES.length) {
            report("REGRESSION " + (failed == 0 ? "PASS" : "FAIL") + " passed="+passed+" failed="+failed);
            active = null;
            return;
        }
        ticks = 0;
        replacementSeen = false;
        mining = null;
        gathering = null;
        for (int x=-16;x<=16;x++) for (int z=-16;z<=16;z++) {
            block(x,-1,z,Blocks.STONE);
            for (int y=0;y<=5;y++) block(x,y,z,Blocks.AIR);
        }
        bot = create("reg_actor",0,0);
        bot.player().getInventory().setSelectedSlot(4);
        report("REGRESSION START " + NAMES[scenario]);
        switch (scenario) {
            case 0 -> {
                bot.player().getInventory().setItem(20,new ItemStack(Items.DIAMOND_PICKAXE));
                bot.player().getInventory().setItem(4,new ItemStack(Items.APPLE,3));
                block(1,0,0,Blocks.STONE);
                mining=bot.miner().breakBlock(p(1,0,0));
            }
            case 1 -> { block(1,0,0,Blocks.DIRT); mining=bot.miner().breakBlock(p(1,0,0)); mining.cancel(); }
            case 2 -> { block(1,0,0,Blocks.DIAMOND_ORE); mining=bot.miner().breakBlock(p(1,0,0)); }
            case 3 -> { block(1,0,0,Blocks.BEDROCK); mining=bot.miner().breakBlock(p(1,0,0)); }
            case 4,5 -> {
                if(scenario==5) for(int i=0;i<36;i++) bot.player().getInventory().setItem(i,new ItemStack(Items.COBBLESTONE,64));
                block(3,0,0,Blocks.DIRT);
                gathering=bot.collector().gather(new GatherRequest(Blocks.DIRT,Items.DIRT,1),new GatherPolicy(6,300,3));
            }
            case 6 -> gathering=bot.collector().gather(new GatherRequest(Blocks.DIAMOND_BLOCK,Items.DIAMOND_BLOCK,1),new GatherPolicy(4,100,3));
            case 7,8 -> {
                block(5,0,0,Blocks.DIRT);
                gathering=bot.collector().gather(new GatherRequest(Blocks.DIRT,Items.DIRT,1),new GatherPolicy(6,scenario==7?1:100,3));
                if(scenario==8) gathering.cancel();
            }
            case 9 -> {
                target=create("reg_target",2,0);
                bot.player().getInventory().setItem(15,new ItemStack(Items.WOODEN_SWORD));
                bot.player().getInventory().setItem(20,new ItemStack(Items.DIAMOND_SWORD));
                bot.combat().fight(target.player(),CombatPolicy.standard().swordOnly());
            }
            case 10 -> {
                target=create("reg_target",12,0);
                bot.player().getInventory().setItem(20,new ItemStack(Items.BOW));
                bot.player().getInventory().setItem(21,new ItemStack(Items.ARROW,64));
                bot.combat().fight(target.player(),CombatPolicy.expert().bowOnly());
            }
            case 11 -> {
                for(int x=3;x<=14;x++) for(int z=-16;z<=16;z++) block(x,0,z,Blocks.STONE);
                target=create("reg_target",8,1);
                bot.player().getInventory().setItem(20,new ItemStack(Items.IRON_SWORD));
                bot.combat().fight(target.player(),CombatPolicy.expert().swordOnly());
            }
            case 12,13 -> {
                block(3,0,0,Blocks.DIRT);
                if(scenario==12) {
                    for(int x=2;x<=4;x++) for(int z=-1;z<=1;z++) {
                        if(x!=3 || z!=0) block(x,0,z,Blocks.BEDROCK);
                        block(x,1,z,Blocks.BEDROCK);
                    }
                } else {
                    block(1,-1,0,Blocks.AIR);
                    block(2,-1,-1,Blocks.AIR);
                    block(2,-1,1,Blocks.AIR);
                }
                gathering=bot.collector().gather(new GatherRequest(Blocks.DIRT,Items.DIRT,1),new GatherPolicy(6,300,3));
            }
        }
    }

    private void tick() {
        try {
            ticks++;
            if(scenario>=9 && scenario<=11 && ticks%100==0) report("REGRESSION DEBUG "+NAMES[scenario]
                +" actor="+bot.player().position()+" target="+target.player().position()
                +" hp="+target.player().getHealth()+" arrows="+bot.player().getInventory().getItem(21).getCount()
                +" combat="+bot.combat().status()+" using="+bot.player().isUsingItem());
            switch(scenario) {
                case 0 -> { if(mining.completion().isDone()) finish(mining.status()==MiningStatus.SUCCESS
                    && level.getBlockState(p(1,0,0)).isAir() && bot.player().getInventory().getSelectedSlot()==4
                    && bot.player().getInventory().getItem(4).is(Items.APPLE)
                    && bot.player().getInventory().getItem(20).is(Items.DIAMOND_PICKAXE), mining.completion().join().toString()); }
                case 1 -> finish(mining.status()==MiningStatus.CANCELLED && bot.player().getInventory().getSelectedSlot()==4
                    && level.getBlockState(p(1,0,0)).is(Blocks.DIRT), "cancel preserves block and selected slot");
                case 2 -> { if(mining.completion().isDone()) finish(mining.status()==MiningStatus.FAILED
                    && mining.completion().join().reason().equals("no_suitable_tool") && bot.player().getInventory().getSelectedSlot()==4,"missing tool"); }
                case 3 -> { if(mining.completion().isDone()) finish(mining.status()==MiningStatus.FAILED
                    && level.getBlockState(p(1,0,0)).is(Blocks.BEDROCK), mining.completion().join().reason()); }
                case 4 -> { if(gathering.completion().isDone()) finish(gathering.status()==GatherStatus.SUCCESS
                    && gathering.collectedItems()>=1 && level.getBlockState(p(3,0,0)).isAir(),gathering.completion().join().toString()); }
                case 5,6 -> { if(gathering.completion().isDone()) finish(gathering.status()==GatherStatus.FAILED
                    && gathering.completion().join().reason().equals(scenario==5?"inventory_full":"no_resources"),gathering.completion().join().toString()); }
                case 7,8 -> { if(gathering.completion().isDone()) finish(gathering.status()==(scenario==7?GatherStatus.FAILED:GatherStatus.CANCELLED)
                    && (scenario==8 || gathering.completion().join().reason().equals("timeout"))
                    && bot.navigator().status()!=NavigationStatus.RUNNING && bot.navigator().status()!=NavigationStatus.COMPUTING
                    && bot.miner().status()!=MiningStatus.RUNNING, gathering.completion().join().toString()); }
                case 9 -> {
                    if(!replacementSeen && bot.player().getMainHandItem().is(Items.DIAMOND_SWORD)) {
                        replacementSeen=true;
                        bot.player().getMainHandItem().setDamageValue(bot.player().getMainHandItem().getMaxDamage()-1);
                    } else if(replacementSeen && bot.player().getMainHandItem().is(Items.WOODEN_SWORD)
                        && target.player().getHealth()<20) finish(true,"best sword selected; depleted sword replaced; real melee damage");
                }
                case 10 -> { if(target.player().getHealth()<20) finish(bot.player().getInventory().getItem(21).getCount()<64,"arrow consumed and target damaged"); }
                case 11 -> { if(bot.player().getY()>=200.9 && bot.player().getX()>1027.5 && target.player().getHealth()<20)
                    finish(true,"climbed step and damaged target"); }
                case 12 -> { if(gathering.completion().isDone()) finish(gathering.status()==GatherStatus.FAILED
                    && level.getBlockState(p(3,0,0)).is(Blocks.DIRT),gathering.completion().join().toString()); }
                case 13 -> { if(gathering.completion().isDone()) finish(gathering.status()==GatherStatus.SUCCESS
                    && gathering.collectedItems()>=1,gathering.completion().join().toString()); }
            }
            if(active!=null && ticks>400) finish(false,"timed out after 400 ticks");
        } catch(Exception e) {
            LoggerFactory.getLogger("navigation_ai_regression").error("Regression exception",e);
            finish(false,e.toString());
        }
    }
    private void finish(boolean ok,String detail) {
        if(ok) passed++; else failed++;
        report("REGRESSION " + (ok?"OK ":"ERROR ") + NAMES[scenario]+": "+detail);
        next();
    }
    private void report(String text) { source.sendSystemMessage(Component.literal(text)); }
}
