package dev.navigationaitest;

import dev.navigationai.NavigationAiMod;
import dev.navigationai.api.*;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import com.mojang.brigadier.arguments.IntegerArgumentType;

/** Real-server integration tests. Run only in a disposable world. */
final class VillagerRegression {
    private static VillagerRegression active;
    private static CommandSourceStack persistenceSource;
    private static int persistenceCheckTick;
    private static final String[] NAMES = {"real_body_identity", "inventory_equipment", "direct_movement",
            "navigation", "door", "mining", "gathering", "excavation", "melee_attribution",
            "bow_ammunition_owner", "shield", "activities", "dimension", "detach", "death_drops",
            "entity_follow", "bridge_placement", "staircase", "sneak_ledge", "unload_restore", "baby_noai", "bow_unforced", "underwater_mining"};
    private final CommandSourceStack source;
    private final ServerLevel level;
    private final NavigationPolicy safe = new NavigationPolicy(false, false, false, false, 20000, 512, 12, 100, 3);
    private BotHandle bot;
    private Cow target;
    private NavigationTask navigation;
    private MiningTask mining;
    private GatherTask gather;
    private ExcavationTask excavation;
    private CombatTask combat;
    private ActivityTask activity;
    private int scenario = -1, ticks, failures;
    private float initialHealth;
    private boolean condition;
    private boolean singleCase, finished;

    private VillagerRegression(CommandSourceStack source) {
        this.source = source; level = source.getLevel();
        // Fixtures stay loaded between cases so an idle test target cannot be saved
        // just before cleanup and reappear as an unrelated obstacle in a later case.
        for(int x=686;x<=689;x++)for(int z=686;z<=689;z++)level.setChunkForced(x,z,true);
    }
    private BotService service() { return NavigationAiMod.runtime(source.getServer()).service(); }
    private BlockPos p(int x, int y, int z) { return new BlockPos(11000 + x, 200 + y, 11000 + z); }
    private Vec3 point(int x) { return Vec3.atBottomCenterOf(p(x, 0, 0)); }
    private void say(String message) { source.sendSystemMessage(Component.literal("VILLAGERS " + message)); }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, env) -> {
            dispatcher.register(Commands.literal("navvillagers").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(cmd -> {
                        if (active != null) return 0;
                        NavigationAiMod.runtime(cmd.getSource().getServer()).service().bots().forEach(BotHandle::remove);
                        active = new VillagerRegression(cmd.getSource()); active.next(); return 1;
                    }));
            dispatcher.register(Commands.literal("navvillagers-case").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(Commands.argument("index",IntegerArgumentType.integer(0,NAMES.length-1)).executes(cmd -> {
                        if(active!=null)return 0;
                        NavigationAiMod.runtime(cmd.getSource().getServer()).service().bots().forEach(BotHandle::remove);
                        active=new VillagerRegression(cmd.getSource());
                        active.scenario=IntegerArgumentType.getInteger(cmd,"index")-1;
                        active.singleCase=true;active.next();return 1;
                    })));
            dispatcher.register(Commands.literal("navvillagers-save").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(cmd -> persistencePrepare(cmd.getSource())));
            dispatcher.register(Commands.literal("navvillagers-restore").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(cmd -> persistenceVerify(cmd.getSource())));
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (active != null) active.tick();
            if (persistenceSource != null && server.getTickCount() >= persistenceCheckTick) {
                var checkSource=persistenceSource; persistenceSource=null; persistenceVerifyNow(checkSource);
            }
        });
    }

    private void next() {
        if (bot != null && bot.state() != BotState.REMOVED) bot.remove();
        if (target != null && !target.isRemoved()) target.discard();
        if (++scenario == NAMES.length) {
            say((failures == 0 ? "PASS" : "FAIL") + " passed=" + (NAMES.length - failures) + " failed=" + failures);
            active = null; return;
        }
        ticks = 0; condition = false; navigation = null; mining = null; gather = null;
        excavation = null; combat = null; activity = null; target = null;
        for (int x = -3; x <= 18; x++) for (int z = -3; z <= 3; z++) {
            level.setBlock(p(x, -1, z), Blocks.BEDROCK.defaultBlockState(), 3);
            for (int y = 0; y <= 4; y++) level.setBlock(p(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        for (var item : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(Vec3.atLowerCornerOf(p(-4,-1,-4)), Vec3.atLowerCornerOf(p(19,6,4))))) item.discard();
        bot = service().createVillager(BotCreateOptions.at("villager_test", level, point(0)));
        say("START " + NAMES[scenario]);
        try {
            switch (scenario) {
                case 0 -> {
                    boolean rejected = false;
                    try { bot.player(); } catch (UnsupportedOperationException expected) { rejected = true; }
                    condition=rejected && bot.kind() == BotKind.VILLAGER && bot.entity() instanceof Villager
                            && source.getServer().getPlayerList().getPlayer(bot.id()) == null
                            && service().attach((Villager)bot.entity()) == bot;
                }
                case 1 -> {
                    ItemStack diamonds = new ItemStack(Items.DIAMOND, 13);
                    boolean inserted = bot.inventory().insert(diamonds).isEmpty();
                    bot.inventory().insert(new ItemStack(Items.SHIELD));
                    bot.inventory().insert(new ItemStack(Items.DIAMOND_CHESTPLATE));
                    boolean equipped = bot.inventory().equip(1, EquipmentSlot.OFFHAND)
                            && bot.inventory().equip(2, EquipmentSlot.CHEST);
                    var snapshot = bot.inventory().snapshot();
                    snapshot.slots().getFirst().setCount(1);
                    int count = bot.inventory().count(Items.DIAMOND);
                    int removed = bot.inventory().extract(0, 5).getCount();
                    finish(inserted && diamonds.getCount() == 13 && equipped && count == 13 && removed == 5
                            && bot.inventory().count(Items.DIAMOND) == 8
                            && bot.entity().getOffhandItem().is(Items.SHIELD), "inventory + equipment=" + equipped);
                }
                case 2 -> { bot.actions().look(-90, 0); bot.actions().setMovement(0, 1, false, false, false); }
                case 3 -> navigation = bot.navigator().goTo(new NavigationGoal.PositionGoal(point(8), .4), safe);
                case 4 -> {
                    for (int x = 2; x <= 4; x++) for (int y = 0; y <= 3; y++) {
                        level.setBlock(p(x,y,-1), Blocks.BEDROCK.defaultBlockState(),3);
                        level.setBlock(p(x,y,1), Blocks.BEDROCK.defaultBlockState(),3);
                    }
                    level.setBlock(p(3,0,0), Blocks.OAK_DOOR.defaultBlockState(), 3);
                    level.setBlock(p(3,1,0), Blocks.OAK_DOOR.defaultBlockState().setValue(
                            net.minecraft.world.level.block.DoorBlock.HALF,
                            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER),3);
                    navigation = bot.navigator().goTo(new NavigationGoal.PositionGoal(point(7), .4),safe);
                }
                case 5 -> {
                    tools(); level.setBlock(p(2,0,0),Blocks.STONE.defaultBlockState(),3);
                    mining = bot.miner().breakBlock(p(2,0,0));
                }
                case 6 -> {
                    tools(); level.setBlock(p(6,0,0),Blocks.OAK_LOG.defaultBlockState(),3);
                    gather = bot.collector().gather(new GatherRequest(Blocks.OAK_LOG, Items.OAK_LOG,1), new GatherPolicy(10,1200,3),safe);
                }
                case 7 -> {
                    tools();
                    for (int x=1;x<=3;x++) for(int y=0;y<2;y++) level.setBlock(p(x,y,0),Blocks.STONE.defaultBlockState(),3);
                    excavation=bot.miner().excavate(new ExcavationRequest(p(0,0,0),Direction.EAST,ExcavationMode.TUNNEL,3),
                            new ExcavationPolicy(10,1800,1,true,true));
                }
                case 8 -> {
                    bot.inventory().insert(new ItemStack(Items.DIAMOND_SWORD)); target(2);
                    combat=bot.combat().fight(target,CombatPolicy.expert().swordOnly());
                }
                case 9,21 -> {
                    if(scenario==21)for(int x=686;x<=689;x++)for(int z=686;z<=689;z++)level.setChunkForced(x,z,false);
                    bot.inventory().insert(new ItemStack(Items.BOW));
                    bot.inventory().insert(new ItemStack(Items.ARROW,16)); target(10);
                    combat=bot.combat().fight(target,CombatPolicy.expert().bowOnly());
                }
                case 10 -> {
                    bot.inventory().insert(new ItemStack(Items.IRON_SWORD));
                    bot.inventory().insert(new ItemStack(Items.SHIELD)); bot.inventory().equip(1,EquipmentSlot.OFFHAND);
                    target(2); target.setInvulnerable(true);
                    combat=bot.combat().fight(target,CombatPolicy.expert().swordOnly());
                }
                case 11 -> {
                    tools(); level.setBlock(p(7,0,0),Blocks.STONE.defaultBlockState(),3);
                    activity=bot.activities().run(List.of(new BotActivity.Navigate(new NavigationGoal.PositionGoal(point(5),.4),safe),
                            new BotActivity.Mine(p(7,0,0)),new BotActivity.Wait(10),
                            new BotActivity.Navigate(new NavigationGoal.ReturnGoal(point(0),.4),safe)));
                }
                case 12 -> {
                    ServerLevel other=source.getServer().getLevel(Level.NETHER);
                    for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++) {
                        other.setBlock(p(x,-1,z),Blocks.BEDROCK.defaultBlockState(),3);
                        for(int y=0;y<4;y++)other.setBlock(p(x,y,z),Blocks.AIR.defaultBlockState(),3);
                    }
                    navigation=bot.navigator().goTo(new NavigationGoal.DimensionGoal(Level.NETHER,point(0),.5),safe);
                }
                case 13 -> {
                    bot.inventory().insert(new ItemStack(Items.DIAMOND,3));
                    var entity=bot.entity(); UUID id=bot.id();
                    bot.navigator().goTo(new NavigationGoal.PositionGoal(point(8),.4),safe);
                    bot.detach();
                    finish(entity.isAlive() && level.getEntity(id)==entity && service().find(id).isEmpty()
                            && bot.state()==BotState.REMOVED, "entity retained, controller detached");
                    entity.discard();
                }
                case 14 -> {
                    bot.inventory().insert(new ItemStack(Items.DIAMOND,3));
                    bot.inventory().insert(new ItemStack(Items.EMERALD,5));
                    bot.entity().hurtServer(level,bot.entity().damageSources().genericKill(),1000);
                }
                case 15 -> {
                    target(8);
                    navigation=bot.navigator().follow(new NavigationGoal.EntityFollowGoal(target,2),safe);
                }
                case 16 -> {
                    bot.inventory().insert(new ItemStack(Items.STONE,16));
                    for(int x=3;x<=8;x++)for(int z=-3;z<=3;z++)level.setBlock(p(x,-1,z),Blocks.AIR.defaultBlockState(),3);
                    navigation=bot.navigator().goTo(new NavigationGoal.PositionGoal(point(10),.4),
                            new NavigationPolicy(false,true,false,false,20000,512,12,100,3));
                }
                case 17 -> {
                    tools();
                    for(int x=1;x<=3;x++)for(int z=-1;z<=1;z++)for(int y=-x-1;y<=2;y++)
                        level.setBlock(p(x,y,z),Blocks.STONE.defaultBlockState(),3);
                    excavation=bot.miner().excavate(new ExcavationRequest(p(0,0,0),Direction.EAST,ExcavationMode.STAIRCASE_DOWN,3),
                            new ExcavationPolicy(20,1800,1,true,true));
                }
                case 18 -> {
                    for(int x=3;x<=18;x++)for(int z=-3;z<=3;z++)level.setBlock(p(x,-1,z),Blocks.AIR.defaultBlockState(),3);
                    bot.actions().look(-90,0);bot.actions().setMovement(0,1,false,false,true);
                }
                case 19 -> {
                    bot.inventory().insert(new ItemStack(Items.DIAMOND,7));
                    ((Villager)bot.entity()).getInventory().setItem(0,new ItemStack(Items.BREAD,4));
                    ((Villager)bot.entity()).setVillagerXp(42);
                    bot.activities().run(List.of(new BotActivity.Wait(100)));
                    var out=net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,level.registryAccess());
                    bot.entity().saveWithoutId(out);
                    var saved=out.buildResult();UUID id=bot.id();var old=bot;
                    bot.entity().setRemoved(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
                    var loaded=new Villager(EntityTypes.VILLAGER,level);
                    loaded.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,level.registryAccess(),saved));
                    level.addFreshEntity(loaded);bot=service().attach(loaded);
                    condition=old.state()==BotState.REMOVED && id.equals(bot.id())
                            && bot.inventory().count(Items.DIAMOND)==7 && loaded.getVillagerXp()==42
                            && loaded.getInventory().getItem(0).getCount()==4;
                }
                case 20 -> {
                    ((Villager)bot.entity()).setBaby(true);((Villager)bot.entity()).setNoAi(true);
                    navigation=bot.navigator().goTo(new NavigationGoal.PositionGoal(point(6),.4),safe);
                }
                case 22 -> {
                    tools();
                    for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=0;y<=2;y++)
                        level.setBlock(p(x,y,z),Blocks.WATER.defaultBlockState(),3);
                    level.setBlock(p(2,0,0),Blocks.STONE.defaultBlockState(),3);
                    // Delay starting until vanilla has observed submersion.
                    activity=bot.activities().run(List.of(new BotActivity.Wait(5),new BotActivity.Mine(p(2,0,0))));
                }
                default -> throw new IllegalStateException();
            }
        } catch(Throwable failure) { finish(false,failure.toString()); }
    }

    private void tools() {
        bot.inventory().insert(new ItemStack(Items.DIAMOND_PICKAXE));
        bot.inventory().insert(new ItemStack(Items.DIAMOND_AXE));
    }
    private void target(int x) {
        target=new Cow(EntityTypes.COW,level);target.setPos(point(x));target.setNoAi(true);
        level.addFreshEntity(target);initialHealth=target.getHealth();
    }

    private void tick() {
        if(finished)return;
        ticks++;
        if (ticks % 200 == 0) say("PROGRESS " + NAMES[scenario] + " ticks=" + ticks
                + " position=" + bot.entity().position() + " nav=" + bot.navigator().status()
                + " metrics=" + NavigationAiMod.runtime(source.getServer()).metrics() + " debug=" + debug());
        try {
            if(scenario==0 && level.getEntity(bot.id())==bot.entity()) {
                finish(condition,"registered vanilla body");return;
            }
            if(scenario==15 && bot.entity().distanceTo(target)<=2.2) {
                navigation.cancel();finish(true,"followed living entity");return;
            }
            if(scenario==18 && ticks==120) {
                finish(bot.entity().getY()>=199.9 && bot.entity().getX()>11002 && bot.entity().getX()<11003.4,
                        "sneak body="+bot.entity().position());return;
            }
            if(scenario==19 && ticks>=120) {
                finish(condition && bot.activities().status()==ActivityStatus.SUCCESS,"restored activity="+bot.activities().status());return;
            }
            if(scenario==2 && bot.entity().getX()>=point(3).x) {
                bot.actions().stop();finish(true,"moved "+bot.entity().position());return;
            }
            if(navigation!=null && navigation.completion().isDone()) {
                boolean identity=scenario!=12 || bot.entity().level().dimension().equals(Level.NETHER)
                        && bot.state()==BotState.ACTIVE && service().find(bot.id()).orElseThrow()==bot;
                boolean materials=scenario!=16 || bot.inventory().count(Items.STONE)<16;
                finish(navigation.status()==NavigationStatus.SUCCESS && identity && materials,"navigation="+navigation.completion().getNow(null)+" body="+bot.entity().position());return;
            }
            if(mining!=null && mining.completion().isDone()) {
                finish(mining.status()==MiningStatus.SUCCESS && level.getBlockState(p(2,0,0)).isAir(),"mining="+mining.status());return;
            }
            if(gather!=null && gather.completion().isDone()) {
                finish(gather.status()==GatherStatus.SUCCESS && bot.inventory().count(Items.OAK_LOG)>=1,"gather="+gather.status());return;
            }
            if(excavation!=null && excavation.completion().isDone()) {
                finish(excavation.status()==ExcavationStatus.SUCCESS && bot.entity().position().distanceTo(point(0))<=.5,
                        "excavation="+excavation.completion().getNow(null));return;
            }
            if(scenario==8 && target.getHealth()<initialHealth) {
                finish(target.getLastHurtByMob()==bot.entity() && bot.entity().getHealth()>=19,
                        "damage="+(initialHealth-target.getHealth())+" attributed="+(target.getLastHurtByMob()==bot.entity()));return;
            }
            if(scenario==9 || scenario==21) {
                for(var arrow:level.getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class,
                        bot.entity().getBoundingBox().inflate(20))) if(arrow.getOwner()==bot.entity()) {
                    condition=true;
                    if(singleCase && (arrow.tickCount<3 || ticks%50==0))say("ARROW t="+ticks+" age="+arrow.tickCount+" pos="+arrow.position()
                            +" motion="+arrow.getDeltaMovement()+" target="+target.position()+" health="+target.getHealth());
                }
                if(singleCase && ticks%10==0)say("AIM t="+ticks+" yaw="+bot.entity().getYRot()+" pitch="+bot.entity().getXRot()
                        +" draw="+bot.entity().getTicksUsingItem()+" combat="+bot.combat().diagnostics());
                if(target.getHealth()<initialHealth) {
                    finish(condition && bot.inventory().count(Items.ARROW)<16,"real arrow owner="+condition);return;
                }
            }
            if(scenario==10 && bot.entity().isBlocking()) {
                float health=bot.entity().getHealth();
                bot.entity().hurtServer(level,target.damageSources().mobAttack(target),4);
                finish(bot.entity().getHealth()==health,"vanilla shield blocked hit");return;
            }
            if(activity!=null && activity.completion().isDone()) {
                boolean terrain=scenario==22 ? level.getBlockState(p(2,0,0)).isAir() && ticks>=20
                        : level.getBlockState(p(7,0,0)).isAir();
                finish(activity.status()==ActivityStatus.SUCCESS && terrain,"activity="+activity.completion().getNow(null));return;
            }
            if(scenario==14 && ticks==2) {
                int diamonds=0,emeralds=0;
                for(var drop:level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(Vec3.atLowerCornerOf(p(-3,-1,-3)),Vec3.atLowerCornerOf(p(3,5,3))))) {
                    if(drop.getItem().is(Items.DIAMOND))diamonds+=drop.getItem().getCount();
                    if(drop.getItem().is(Items.EMERALD))emeralds+=drop.getItem().getCount();
                }
                finish(diamonds==3 && emeralds==5,"diamonds="+diamonds+" emeralds="+emeralds);return;
            }
            if(ticks>700)finish(false,"timeout pos="+bot.entity().position()+" nav="+bot.navigator().status()+" combat="+bot.combat().diagnostics());
        } catch(Throwable error) { finish(false,error.toString()); }
    }

    private void finish(boolean ok,String detail) {
        if(finished)return;
        if(singleCase)finished=true;
        if(!ok)failures++;
        say((ok?"OK ":"BAD ")+NAMES[scenario]+" ticks="+ticks+" "+detail);
        if(singleCase) { say((ok?"PASS":"FAIL")+" passed="+(ok?1:0)+" failed="+(ok?0:1));active=null;return; }
        // Advance next tick: avoid reentering controller callbacks during cancellation.
        source.getServer().execute(() -> { if(active==this) next(); });
    }

    private String debug() {
        StringBuilder out = new StringBuilder();
        for (String name : new String[]{"pendingCapture", "planningDeferred", "waitingForLanding", "recoverAfterLanding", "requestGeneration", "captureGeneration"}) {
            try {
                var field=bot.navigator().getClass().getDeclaredField(name);field.setAccessible(true);
                out.append(name).append('=').append(field.get(bot.navigator())).append(' ');
            } catch (ReflectiveOperationException ignored) { }
        }
        return out.toString();
    }

    private static int persistencePrepare(CommandSourceStack source) {
        var service=NavigationAiMod.runtime(source.getServer()).service();
        for(int x=10999;x<=11001;x++)for(int z=10999;z<=11001;z++) {
            source.getLevel().setBlock(new BlockPos(x,199,z),Blocks.BEDROCK.defaultBlockState(),3);
            for(int y=200;y<=203;y++)source.getLevel().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
        }
        var bot=service.createVillager(BotCreateOptions.at("villager_persist",source.getLevel(),new Vec3(11000.5,200,11000.5)));
        bot.inventory().insert(new ItemStack(Items.DIAMOND,17));
        bot.activities().run(List.of(new BotActivity.Wait(1000)));
        source.sendSystemMessage(Component.literal("VILLAGERS PERSIST READY "+bot.id()));return 1;
    }

    private static int persistenceVerify(CommandSourceStack source) {
        persistenceSource=source;persistenceCheckTick=source.getServer().getTickCount()+40;return 1;
    }
    private static int persistenceVerifyNow(CommandSourceStack source) {
        var service=NavigationAiMod.runtime(source.getServer()).service();
        var matches=service.bots().stream().filter(b->b.name().equals("villager_persist")).toList();
        boolean ok=matches.size()==1;
        if(ok) {
            var bot=matches.getFirst();
            ok=bot.kind()==BotKind.VILLAGER && bot.inventory().count(Items.DIAMOND)==17
                    && bot.activities().status()==ActivityStatus.RUNNING
                    && source.getServer().getPlayerList().getPlayer(bot.id())==null;
        }
        source.sendSystemMessage(Component.literal("VILLAGERS PERSIST "+(ok?"PASS":"FAIL")+" matches="+matches.size()));return ok?1:0;
    }
}
