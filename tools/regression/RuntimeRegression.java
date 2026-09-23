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
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.core.registries.Registries;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Explicit, destructive-to-arena integration test. Run only in a disposable world. */
public final class RuntimeRegression {
    private static RuntimeRegression active;
    private final CommandSourceStack source;
    private final ServerLevel level;
    private BotHandle bot, target;
    private MiningTask mining;
    private GatherTask gathering;
    private NavigationTask navigation;
    private int scenario = -1, ticks, passed, failed;
    private boolean replacementSeen;
    private int finalScenario;
    private static final String[] NAMES = {"mining_inventory_tool_restore", "mining_cancel_before_tick",
        "mining_no_tool_preserves_slot", "unbreakable_block", "gather_and_pickup", "full_inventory",
        "missing_resource", "gather_timeout_cancels_navigation", "gather_cancel",
        "best_sword_and_broken_weapon_replacement", "bow_inventory_arrows_damage", "combat_one_block_step",
        "unreachable_resource", "retry_other_side", "navigation_breaking", "navigation_bridge",
        "navigation_door", "navigation_ladder", "navigation_dynamic_obstacle",
        "navigation_stair_geometry", "navigation_partial_surface", "navigation_water",
        "navigation_copper_door", "navigation_jump_pits", "navigation_crouch_tunnel",
        "navigation_long_wall_frontier", "navigation_block_goal_solid", "navigation_controlled_drop",
        "navigation_fence_gate", "navigation_bridge_cancel_rollback", "navigation_multi_goal",
        "navigation_patrol", "navigation_look_at", "navigation_dimension_goal", "navigation_escort",
        "navigation_waterlogged_geometry", "navigation_jump_with_doors_at_edges",
        "navigation_open_door", "navigation_open_trapdoor", "navigation_open_overhead_trapdoor",
        "navigation_open_trapdoor_corner", "navigation_long_deep_water", "navigation_long_flat_route",
        "navigation_occupied_destination"};
    private boolean dynamicObstaclePlaced;

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (active != null) active.tick();
        });
    }

    public static int start(CommandSourceStack source) {
        return startCase(source, 0, NAMES.length - 1);
    }

    public static int startCase(CommandSourceStack source, int first, int last) {
        if (active != null) return 0;
        if (first < 0 || last >= NAMES.length || first > last) return 0;
        // Regression scenarios own their disposable arena. Remove persisted
        // practice bots first so their idle entities cannot affect geometry
        // or the adaptive runtime profile.
        NavigationAiMod.runtime(source.getServer()).service().bots().forEach(BotHandle::remove);
        active = new RuntimeRegression(source);
        active.scenario = first - 1;
        active.finalScenario = last;
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
        if (++scenario > finalScenario) {
            report("REGRESSION " + (failed == 0 ? "PASS" : "FAIL") + " passed="+passed+" failed="+failed);
            active = null;
            return;
        }
        ticks = 0;
        replacementSeen = false;
        dynamicObstaclePlaced = false;
        mining = null;
        gathering = null;
        navigation = null;
        for (int x=-16;x<=16;x++) for (int z=-16;z<=16;z++) {
            block(x,-1,z,Blocks.STONE);
            for (int y=0;y<=5;y++) block(x,y,z,Blocks.AIR);
        }
        // The frontier scenario deliberately extends beyond the local arena.
        // Clear that extension once before the run and once after the
        // scenario so later local goals do not inherit its wall.
        if (scenario == 0 || scenario == 26) {
            for (int x=17; x<=64; x++) for (int z=-8; z<=8; z++) {
                block(x,-1,z,Blocks.STONE);
                for (int y=0; y<=5; y++) block(x,y,z,Blocks.AIR);
            }
        }
        bot = create("reg_actor",0,scenario == 27 ? 3 : 0);
        bot.player().getInventory().setSelectedSlot(4);
        report("REGRESSION START " + NAMES[scenario]);
        switch (scenario) {
            case 0 -> {
                bot.player().getInventory().setItem(20,new ItemStack(Items.WOODEN_PICKAXE));
                ItemStack enchantedPick = new ItemStack(Items.IRON_PICKAXE);
                enchantedPick.enchant(source.getServer().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                        .getOrThrow(Enchantments.EFFICIENCY), 5);
                bot.player().getInventory().setItem(21, enchantedPick);
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
            case 14 -> {
                for (int z=-16; z<=16; z++) {
                    block(2,0,z,Blocks.DIRT);
                    block(2,1,z,Blocks.DIRT);
                    block(2,2,z,Blocks.BEDROCK);
                }
                navigation=bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1029.5,200.0,1024.5),0.75),
                    new NavigationPolicy(true,false,false,false,20_000,128,8,0));
            }
            case 15 -> {
                block(2,-1,0,Blocks.AIR);
                bot.player().getInventory().setItem(10,new ItemStack(Items.COBBLESTONE,8));
                navigation=bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1026.5,200.0,1024.5),0.75),
                    new NavigationPolicy(false,true,false,false,20_000,128,8,0));
            }
            case 16 -> {
                // A narrow corridor forces the bot through a closed wooden door.
                for (int x=-16; x<=16; x++) {
                    block(x,0,-1,Blocks.STONE); block(x,1,-1,Blocks.STONE);
                    block(x,0,1,Blocks.STONE); block(x,1,1,Blocks.STONE);
                }
                level.setBlockAndUpdate(p(2,0,0), Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                    .setValue(DoorBlock.FACING, Direction.EAST));
                level.setBlockAndUpdate(p(2,1,0), Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)
                    .setValue(DoorBlock.FACING, Direction.EAST));
                navigation=bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1026.5,200.0,1024.5),0.75),
                    new NavigationPolicy(false,false,false,false,20_000,128,8,0));
            }
            case 17 -> {
                // A ladder is the only route to a higher platform.
                for (int y=0; y<=4; y++) {
                    block(0,y,-1,Blocks.STONE);
                    block(0,y,1,Blocks.STONE);
                }
                for (int y=0; y<=3; y++) {
                    level.setBlockAndUpdate(p(0,y,0), Blocks.LADDER.defaultBlockState()
                        .setValue(LadderBlock.FACING, Direction.SOUTH));
                }
                navigation=bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1024.5,203.0,1024.5),0.75),
                    new NavigationPolicy(false,false,false,false,20_000,128,8,0));
            }
            case 18 -> {
                navigation=bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1029.5,200.0,1024.5),0.75),
                    new NavigationPolicy(false,false,false,false,20_000,128,8,0));
            }
            case 19 -> {
                // A staircase is the only route to the raised platform. The
                // path must use the support below each successive step.
                for (int step=0; step<3; step++) {
                    level.setBlockAndUpdate(p(2+step,step,0),
                        Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
                }
                block(5,2,0,Blocks.STONE);
                navigation=bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1029.5,203.0,1024.5),0.75),
                    new NavigationPolicy(false,false,false,false,20_000,128,8,0));
            }
            case 20 -> {
                // A narrow corridor forces traversal over a bottom slab. The
                // slab is a partial collision shape, not a full block.
                for (int x=-16; x<=16; x++) {
                    block(x,0,-1,Blocks.STONE); block(x,1,-1,Blocks.STONE);
                    block(x,0,1,Blocks.STONE); block(x,1,1,Blocks.STONE);
                }
                level.setBlockAndUpdate(p(2,0,0),
                    Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                navigation=bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1029.5,200.0,1024.5),0.75),
                    new NavigationPolicy(false,false,false,false,20_000,128,8,0));
            }
            case 21 -> {
                // Water is allowed explicitly and occupies the only corridor.
                for (int x=-16; x<=16; x++) {
                    block(x,0,-1,Blocks.STONE); block(x,1,-1,Blocks.STONE);
                    block(x,0,1,Blocks.STONE); block(x,1,1,Blocks.STONE);
                }
                for (int x=2; x<=4; x++) block(x,0,0,Blocks.WATER);
                navigation=bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1029.5,200.0,1024.5),0.75),
                    new NavigationPolicy(false,false,true,false,20_000,128,8,0));
            }
            case 22 -> {
                // Copper doors are hand-openable in current Minecraft, but
                // are intentionally not members of WOODEN_DOORS.
                for (int x=-16; x<=16; x++) {
                    block(x,0,-1,Blocks.STONE); block(x,1,-1,Blocks.STONE);
                    block(x,0,1,Blocks.STONE); block(x,1,1,Blocks.STONE);
                }
                net.minecraft.world.level.block.Block copperDoor = Blocks.COPPER_DOOR.weathering().unaffected();
                level.setBlockAndUpdate(p(2,0,0), copperDoor.defaultBlockState()
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                    .setValue(DoorBlock.FACING, Direction.EAST));
                level.setBlockAndUpdate(p(2,1,0), copperDoor.defaultBlockState()
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)
                    .setValue(DoorBlock.FACING, Direction.EAST));
                navigation=bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1026.5,200.0,1024.5),0.75),
                    new NavigationPolicy(false,false,false,false,20_000,128,8,0));
            }
            case 23 -> {
                // Three one-block-wide corridors gaps require jumps of one,
                // two and three blocks respectively.
                for (int x=-16; x<=16; x++) {
                    block(x,0,-1,Blocks.STONE); block(x,1,-1,Blocks.STONE);
                    block(x,0,1,Blocks.STONE); block(x,1,1,Blocks.STONE);
                }
                for (int x : new int[]{2, 5, 6, 9, 10, 11}) block(x,-1,0,Blocks.AIR);
                navigation=bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1039.5,200.0,1024.5),0.75),
                    new NavigationPolicy(false,false,false,false,20_000,128,8,0));
            }
            case 24 -> {
                // A pair of non-hand-openable trapdoors leaves a tight tunnel
                // that is passable only while the bot keeps sneak held.
                for (int x=-16; x<=16; x++) {
                    block(x,0,-1,Blocks.STONE); block(x,1,-1,Blocks.STONE);
                    block(x,0,1,Blocks.STONE); block(x,1,1,Blocks.STONE);
                }
                net.minecraft.world.level.block.Block trapdoor = Blocks.IRON_TRAPDOOR;
                for (int x=2; x<=3; x++) {
                    level.setBlockAndUpdate(p(x,0,0), trapdoor.defaultBlockState()
                        .setValue(TrapDoorBlock.HALF, Half.BOTTOM));
                    level.setBlockAndUpdate(p(x,1,0), trapdoor.defaultBlockState()
                        .setValue(TrapDoorBlock.HALF, Half.TOP));
                }
                navigation=bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1026.5,200.0,1024.5),0.75),
                    new NavigationPolicy(false,false,false,false,20_000,128,8,0));
            }
            case 25 -> {
                // The goal and wall extend beyond one local snapshot.  The
                // planner must choose a frontier route around the wall and
                // continue from successive snapshots until it reaches x=40.
                for (int x=17; x<=48; x++) for (int z=-8; z<=8; z++) {
                    block(x, -1, z, Blocks.STONE);
                    block(x, 0, z, Blocks.AIR);
                    block(x, 1, z, Blocks.AIR);
                }
                for (int x=4; x<=38; x++) for (int z=-1; z<=1; z++) {
                    block(x, 0, z, Blocks.STONE);
                    block(x, 1, z, Blocks.STONE);
                }
                navigation=bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1064.5,200.0,1024.5),0.75),
                    new NavigationPolicy(false,false,false,false,20_000,512,10,0));
            }
            case 26 -> {
                // A BlockGoal points at the solid target block.  The planner
                // must finish from an adjacent walkable cell when breaking is
                // disabled, then satisfy the goal tolerance at the block.
                block(3, 0, 0, Blocks.STONE);
                block(3, 1, 0, Blocks.STONE);
                navigation=bot.navigator().goTo(
                    new NavigationGoal.BlockGoal(p(3,0,0)),
                    new NavigationPolicy(false,false,false,false,20_000,128,8,0));
            }
            case 27 -> {
                // Start on a raised ledge. The only useful transition is a
                // controlled three-block drop onto the lower floor.
                block(0,2,0,Blocks.STONE);
                navigation=bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1025.5,200.0,1024.5),0.75),
                    new NavigationPolicy(false,false,false,false,20_000,128,8,0,3));
            }
            case 28 -> {
                // Fence gates are hand-openable collision shapes, distinct
                // from doors and wooden trapdoors.
                for (int x=-16; x<=16; x++) {
                    block(x,0,-1,Blocks.STONE); block(x,1,-1,Blocks.STONE);
                    block(x,0,1,Blocks.STONE); block(x,1,1,Blocks.STONE);
                }
                level.setBlockAndUpdate(p(2,0,0), Blocks.OAK_FENCE_GATE.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.FenceGateBlock.FACING, Direction.EAST));
                navigation=bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1029.5,200.0,1024.5),0.75),
                    new NavigationPolicy(false,false,false,false,20_000,128,8,0));
            }
            case 29 -> {
                // Cancelling a bridge build must compensate the partial
                // placement and return the consumed block to the inventory.
                for (int x=-16; x<=16; x++) {
                    block(x,0,-1,Blocks.STONE); block(x,1,-1,Blocks.STONE);
                    block(x,0,1,Blocks.STONE); block(x,1,1,Blocks.STONE);
                }
                for (int x=2; x<=5; x++) block(x,-1,0,Blocks.AIR);
                bot.player().getInventory().setItem(10,new ItemStack(Items.COBBLESTONE,8));
                navigation=bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1032.5,200.0,1024.5),0.75),
                    new NavigationPolicy(false,true,false,false,20_000,256,8,0));
            }
            case 30 -> navigation=bot.navigator().goTo(new NavigationGoal.MultiGoal(List.of(
                    new NavigationGoal.PositionGoal(new Vec3(1027.5,200.0,1024.5),0.75),
                    new NavigationGoal.PositionGoal(new Vec3(1030.5,200.0,1024.5),0.75),
                    new NavigationGoal.PositionGoal(new Vec3(1033.5,200.0,1024.5),0.75))),
                    new NavigationPolicy(false,false,false,false,20_000,128,8,0));
            case 31 -> navigation=bot.navigator().goTo(new NavigationGoal.PatrolGoal(List.of(
                    new Vec3(1027.5,200.0,1024.5), new Vec3(1030.5,200.0,1024.5),
                    new Vec3(1033.5,200.0,1024.5)), false),
                    new NavigationPolicy(false,false,false,false,20_000,128,8,0));
            case 32 -> navigation=bot.navigator().goTo(
                    new NavigationGoal.LookAtGoal(new Vec3(1029.5,201.5,1024.5),0.75),
                    new NavigationPolicy(false,false,false,false,20_000,128,8,0));
            case 33 -> {
                ServerLevel nether = source.getServer().getLevel(Level.NETHER);
                if (nether == null) throw new IllegalStateException("nether unavailable");
                for (int x=1022; x<=1036; x++) for (int z=1022; z<=1026; z++) {
                    nether.setBlock(new BlockPos(x,199,z), Blocks.NETHERRACK.defaultBlockState(), Block.UPDATE_ALL);
                    for (int y=200; y<=202; y++) nether.setBlock(new BlockPos(x,y,z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
                navigation=bot.navigator().goTo(
                        new NavigationGoal.DimensionGoal(Level.NETHER, new Vec3(1028.5,200.0,1024.5),0.75),
                        new NavigationPolicy(false,false,false,false,20_000,128,8,0));
            }
            case 34 -> {
                target=create("reg_target",6,0);
                navigation=bot.navigator().goTo(new NavigationGoal.EscortGoal(target.player(),2.0,4.0),
                        new NavigationPolicy(false,false,false,false,20_000,128,8,0));
            }
            case 35 -> {
                // A waterlogged bottom slab exercises collision shape and
                // fluid state together; it must remain a valid low surface.
                for (int x=-16; x<=16; x++) {
                    block(x,0,-1,Blocks.STONE); block(x,1,-1,Blocks.STONE);
                    block(x,0,1,Blocks.STONE); block(x,1,1,Blocks.STONE);
                }
                level.setBlockAndUpdate(p(2,0,0), Blocks.OAK_SLAB.defaultBlockState()
                        .setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                        .setValue(SlabBlock.WATERLOGGED, true));
                navigation=bot.navigator().goTo(
                        new NavigationGoal.PositionGoal(new Vec3(1029.5,200.0,1024.5),0.75),
                        new NavigationPolicy(false,false,true,false,20_000,128,8,0));
            }
            case 36 -> {
                // A one-wide corridor has a closed door at both the takeoff
                // and landing blocks of a three-block gap.
                for (int x = -16; x <= 16; x++) {
                    block(x, 0, -1, Blocks.STONE); block(x, 1, -1, Blocks.STONE);
                    block(x, 0, 1, Blocks.STONE); block(x, 1, 1, Blocks.STONE);
                }
                for (int x = 2; x <= 4; x++) block(x, -1, 0, Blocks.AIR);
                for (int x : new int[]{1, 5}) {
                    level.setBlockAndUpdate(p(x, 0, 0), Blocks.OAK_DOOR.defaultBlockState()
                        .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                        .setValue(DoorBlock.FACING, Direction.EAST));
                    level.setBlockAndUpdate(p(x, 1, 0), Blocks.OAK_DOOR.defaultBlockState()
                        .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)
                        .setValue(DoorBlock.FACING, Direction.EAST));
                }
                navigation = bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1030.5, 200.0, 1024.5), 0.75),
                    new NavigationPolicy(false, false, false, false, 20_000, 128, 8, 0));
            }
            case 37, 38, 39 -> {
                for (int x = -16; x <= 16; x++) {
                    block(x, 0, -1, Blocks.STONE); block(x, 1, -1, Blocks.STONE);
                    block(x, 0, 1, Blocks.STONE); block(x, 1, 1, Blocks.STONE);
                }
                if (scenario == 37) {
                    level.setBlockAndUpdate(p(2, 0, 0), Blocks.OAK_DOOR.defaultBlockState()
                        .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                        .setValue(DoorBlock.FACING, Direction.EAST)
                        .setValue(DoorBlock.OPEN, true));
                    level.setBlockAndUpdate(p(2, 1, 0), Blocks.OAK_DOOR.defaultBlockState()
                        .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)
                        .setValue(DoorBlock.FACING, Direction.EAST)
                        .setValue(DoorBlock.OPEN, true));
                } else {
                    int y = scenario == 38 ? 0 : 1;
                    level.setBlockAndUpdate(p(2, y, 0), Blocks.OAK_TRAPDOOR.defaultBlockState()
                        .setValue(TrapDoorBlock.FACING, Direction.NORTH)
                        .setValue(TrapDoorBlock.OPEN, true));
                }
                navigation = bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1029.5, 200.0, 1024.5), 0.75),
                    new NavigationPolicy(false, false, false, false, 20_000, 128, 8, 0));
            }
            case 40 -> {
                // An open trapdoor has a vertical side panel. Its center is
                // free, but the panel blocks the south exit. The route must
                // use the short detour around it instead of walking into it.
                for (int x = -1; x <= 4; x++) for (int z = -1; z <= 3; z++) {
                    for (int y = 0; y <= 2; y++) block(x, y, z, Blocks.STONE);
                }
                for (int[] cell : new int[][]{{0,0}, {1,0}, {2,0}, {3,0}, {3,1}, {2,1}, {2,2}}) {
                    for (int y = 0; y <= 2; y++) block(cell[0], y, cell[1], Blocks.AIR);
                }
                level.setBlockAndUpdate(p(2,0,0), Blocks.OAK_TRAPDOOR.defaultBlockState()
                    .setValue(TrapDoorBlock.FACING, Direction.SOUTH)
                    .setValue(TrapDoorBlock.OPEN, true));
                navigation = bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1026.5, 200.0, 1026.5), 0.75),
                    new NavigationPolicy(false, false, false, false, 20_000, 128, 8, 0));
            }
            case 41 -> {
                // A long swim used to fail at the eight-cell water budget.
                // During replanning the bot must keep afloat while the next
                // snapshot is captured over multiple server ticks.
                for (int x = -16; x <= 30; x++) {
                    for (int y = 0; y <= 2; y++) {
                        block(x, y, -1, Blocks.STONE);
                        block(x, y, 1, Blocks.STONE);
                    }
                }
                for (int x = 2; x <= 23; x++) for (int y = -3; y <= 0; y++) {
                    block(x, y, 0, Blocks.WATER);
                }
                for (int x = 2; x <= 23; x++) block(x, 2, 0, Blocks.STONE);
                navigation = bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1050.5, 200.0, 1024.5), 0.75),
                    new NavigationPolicy(false, false, true, false, 40_000, 128, 32, 0));
            }
            case 42 -> {
                for (int x = 17; x <= 34; x++) {
                    block(x, -1, 0, Blocks.STONE);
                    for (int y = 0; y <= 5; y++) block(x, y, 0, Blocks.AIR);
                }
                navigation = bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(new Vec3(1056.5, 200.0, 1024.5), 0.75),
                    new NavigationPolicy(false, false, false, false, 40_000, 128, 32, 0));
            }
            case 43 -> {
                for (int x = -16; x <= 16; x++) {
                    block(x, 0, -1, Blocks.STONE); block(x, 1, -1, Blocks.STONE);
                    block(x, 0, 1, Blocks.STONE); block(x, 1, 1, Blocks.STONE);
                }
                target = create("reg_target", 5, 0);
                navigation = bot.navigator().goTo(
                    new NavigationGoal.PositionGoal(target.player().position(), 1.5),
                    new NavigationPolicy(false, false, false, false, 20_000, 128, 8, 0));
            }
        }
    }

    private void tick() {
        try {
            ticks++;
            if (scenario >= 41 && ticks % 40 == 0) report("REGRESSION DEBUG " + NAMES[scenario]
                    + " tick=" + ticks + " status=" + navigation.status()
                    + " pos=" + bot.player().position() + " swimming=" + bot.player().isSwimming());
            if(scenario>=9 && scenario<=11 && ticks%100==0) report("REGRESSION DEBUG "+NAMES[scenario]
                +" actor="+bot.player().position()+" target="+target.player().position()
                +" hp="+target.player().getHealth()+" arrows="+bot.player().getInventory().getItem(21).getCount()
                +" combat="+bot.combat().status()+" using="+bot.player().isUsingItem());
            switch(scenario) {
                case 0 -> { if(mining.completion().isDone()) finish(mining.status()==MiningStatus.SUCCESS
                    && level.getBlockState(p(1,0,0)).isAir() && bot.player().getInventory().getSelectedSlot()==4
                    && bot.player().getInventory().getItem(4).is(Items.APPLE)
                    && bot.player().getInventory().getItem(20).is(Items.WOODEN_PICKAXE)
                    && bot.player().getInventory().getItem(21).is(Items.IRON_PICKAXE), mining.completion().join().toString()); }
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
            case 14 -> { if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS
                    && level.getBlockState(p(2,0,0)).isAir() && level.getBlockState(p(2,1,0)).isAir(),
                    navigation.completion().join().toString()); }
            case 15 -> { if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS
                    && level.getBlockState(p(2,-1,0)).is(Blocks.COBBLESTONE), navigation.completion().join().toString()); }
            case 16 -> { if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS
                    && level.getBlockState(p(2,0,0)).getValue(DoorBlock.OPEN), navigation.completion().join().toString()); }
            case 17 -> { if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS
                    && bot.player().getY() >= 202.0, navigation.completion().join().toString()); }
            case 18 -> {
                if (!dynamicObstaclePlaced && ticks >= 8) {
                    dynamicObstaclePlaced = true;
                    block(3,0,0,Blocks.STONE); block(3,1,0,Blocks.STONE);
                }
                if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS
                    && bot.player().getX() >= 1028.75 && level.getBlockState(p(3,0,0)).is(Blocks.STONE),
                    navigation.completion().join().toString());
            }
            case 19 -> { if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS
                    && bot.player().getY() >= 202.0, navigation.completion().join().toString()); }
            case 20 -> { if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS
                    && level.getBlockState(p(2,0,0)).is(Blocks.OAK_SLAB), navigation.completion().join().toString()); }
            case 21 -> { if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS
                    && bot.player().getX() >= 1028.75, navigation.completion().join().toString()); }
            case 22 -> { if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS
                    && level.getBlockState(p(2,0,0)).getValue(DoorBlock.OPEN), navigation.completion().join().toString()); }
            case 23 -> { if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS
                    && bot.player().getX() >= 1038.75, navigation.completion().join().toString()
                        + " pos=" + bot.player().position() + " grounded=" + bot.player().onGround()); }
            case 24 -> { if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS
                    && bot.player().getX() >= 1025.75, navigation.completion().join().toString()); }
            case 25 -> { if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS
                    && bot.player().getX() >= 1063.5 && Math.abs(bot.player().getZ()-1024.5) <= 1.5,
                    navigation.completion().join().toString()); }
            case 26 -> { if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS
                    && bot.player().position().distanceTo(Vec3.atCenterOf(p(3,0,0))) <= 1.0
                    && !bot.player().blockPosition().equals(p(3,0,0))
                    && level.getBlockState(p(3,0,0)).is(Blocks.STONE),
                    navigation.completion().join().toString()); }
            case 27 -> { if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS
                    && bot.player().getX() >= 1025.0 && bot.player().getY() < 201.0,
                    navigation.completion().join().toString()); }
            case 28 -> { if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS
                    && level.getBlockState(p(2,0,0)).getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN)
                    && bot.player().getX() >= 1028.5,
                    navigation.completion().join().toString()+" gate="
                        +level.getBlockState(p(2,0,0)).getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN)
                        +" x="+bot.player().getX()); }
            case 29 -> {
                if (level.getBlockState(p(2,-1,0)).is(Blocks.COBBLESTONE)) {
                    navigation.cancel();
                    finish(navigation.completion().join().status()==NavigationStatus.CANCELLED
                            && level.getBlockState(p(2,-1,0)).isAir()
                            && bot.player().getInventory().getItem(10).getCount() == 8,
                            "cancel compensated partial bridge");
                }
            }
            case 30,31 -> { if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS
                    && bot.player().getX() >= 1032.5,
                    navigation.completion().join().toString()+" x="+bot.player().getX()
                        +" goal="+bot.navigator().goal().orElse(null)); }
            case 32 -> { if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS
                    && Math.abs(bot.player().getX() - 1024.5) < 0.25
                    && Math.abs(bot.player().getYRot() + 90.0f) < 25.0f,
                    navigation.completion().join().toString()+" x="+bot.player().getX()+" yaw="+bot.player().getYRot()); }
            case 33 -> { if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS
                    && bot.player().level().dimension().equals(Level.NETHER)
                    && bot.player().getX() >= 1027.75, navigation.completion().join().toString()); }
            case 34 -> {
                if (ticks > 100 && bot.player().distanceTo(target.player()) <= 3.0) {
                    navigation.cancel();
                    finish(navigation.completion().join().status()==NavigationStatus.CANCELLED,
                            "escort reached target distance");
                }
            }
            case 35 -> { if(navigation.completion().isDone()) finish(navigation.status()==NavigationStatus.SUCCESS
                    && level.getBlockState(p(2,0,0)).getValue(SlabBlock.WATERLOGGED)
                    && bot.player().getX() >= 1028.75, navigation.completion().join().toString()); }
            case 36 -> { if (navigation.completion().isDone()) finish(navigation.status() == NavigationStatus.SUCCESS
                    && bot.player().getX() >= 1029.75
                    && level.getBlockState(p(1,0,0)).getValue(DoorBlock.OPEN)
                    && level.getBlockState(p(5,0,0)).getValue(DoorBlock.OPEN),
                    navigation.completion().join().toString() + " pos=" + bot.player().position()
                        + " grounded=" + bot.player().onGround()); }
            case 37, 38, 39 -> { if (navigation.completion().isDone()) finish(
                    navigation.status() == NavigationStatus.SUCCESS && bot.player().getX() >= 1028.75,
                    navigation.completion().join().toString() + " pos=" + bot.player().position()); }
            case 40 -> { if (navigation.completion().isDone()) finish(
                    navigation.status() == NavigationStatus.SUCCESS && bot.player().getZ() >= 1025.75,
                    navigation.completion().join().toString() + " pos=" + bot.player().position()); }
            case 41 -> { if (navigation.completion().isDone()) finish(
                    navigation.status() == NavigationStatus.SUCCESS && bot.player().getX() >= 1049.75,
                    navigation.completion().join().toString() + " pos=" + bot.player().position()); }
            case 42 -> { if (navigation.completion().isDone()) finish(
                    navigation.status() == NavigationStatus.SUCCESS && bot.player().getX() >= 1055.75,
                    navigation.completion().join().toString() + " pos=" + bot.player().position()); }
            case 43 -> { if (navigation.completion().isDone()) finish(
                    navigation.status() == NavigationStatus.SUCCESS
                            && bot.player().distanceTo(target.player()) <= 1.5,
                    navigation.completion().join().toString() + " actor=" + bot.player().position()
                            + " target=" + target.player().position()); }
            }
            if(active!=null && ticks>(scenario == 41 ? 800 : 400)) finish(false,"timed out at " + bot.player().position()
                    + " status=" + (navigation == null ? "none" : navigation.status()));
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
