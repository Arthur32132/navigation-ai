package dev.navigationaitest;

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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Live physics/projectile tests. Rebuilds a small arena at (12000, 200, 12000)
 * and must only run in a disposable world. Existing unrelated bots are retained. */
public final class CombatTacticsRegression {
    private static CombatTacticsRegression active;
    private static final String[] CASES = {"ranged_then_melee", "strict_sword", "ammo_fallback", "health_limit",
            "visibility_limit", "duration_limit", "entity_occlusion", "ledge_retreat", "shield_and_axe",
            "completion_snapshot", "charged_sword_damage"};
    private final CommandSourceStack source;
    private final ServerLevel level;
    private final List<BotHandle> created = new ArrayList<>();
    private BotHandle actor, target, bystander;
    private CombatTask fight;
    private CombatTask callbackFight;
    private int scenario = -1, ticks, failures;
    private boolean rangedShot, switched, axeSeen, shieldSeen;
    private CombatDiagnostics finalSnapshot;
    private float previousTargetHealth;

    private CombatTacticsRegression(CommandSourceStack source) { this.source = source; this.level = source.getLevel(); }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(
                Commands.literal("navcombat").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(command -> start(command.getSource()))));
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (active != null) active.tick(); });
    }

    public static int run(ServerPlayer player) { return start(player.createCommandSourceStack()); }

    private static int start(CommandSourceStack source) {
        if (active != null) return 0;
        active = new CombatTacticsRegression(source);
        active.next();
        return 1;
    }

    private BlockPos p(int x, int y, int z) { return new BlockPos(12000 + x, 200 + y, 12000 + z); }
    private Vec3 point(double x) { return new Vec3(12000 + x, 200, 12000.5); }
    private void block(int x, int y, int z, net.minecraft.world.level.block.Block block) {
        level.setBlock(p(x,y,z), block.defaultBlockState(), 3);
    }
    private void report(String text) { source.sendSystemMessage(Component.literal("COMBAT " + text)); }

    private BotHandle create(String name, double x) {
        BotHandle bot = NavigationAiMod.runtime(source.getServer()).service().create(
                BotCreateOptions.at(name, level, point(x)).withRotation(-90, 0));
        created.add(bot);
        bot.player().getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        bot.player().getInventory().setItem(1, new ItemStack(Items.BOW));
        bot.player().getInventory().setItem(2, new ItemStack(Items.ARROW, 64));
        bot.player().getInventory().setItem(3, new ItemStack(Items.DIAMOND_AXE));
        return bot;
    }

    private CombatPolicy policy() {
        return CombatPolicy.expert().withTactics(CombatTactics.standard().withLimits(300, 100, 0));
    }

    private void next() {
        for (BotHandle bot : created) bot.remove();
        created.clear();
        if (++scenario == CASES.length) {
            report((failures == 0 ? "PASS" : "FAIL") + " passed=" + (CASES.length-failures) + " failed=" + failures);
            active = null;
            return;
        }
        ticks = 0; rangedShot = false; switched = false; axeSeen = false; shieldSeen = false;
        fight = null; callbackFight = null; finalSnapshot = null; bystander = null;
        for (int x = -6; x <= 24; x++) for (int z = -6; z <= 6; z++) {
            block(x,-1,z,Blocks.STONE);
            for (int y = 0; y <= 5; y++) block(x,y,z,Blocks.AIR);
        }
        actor = create("combat_actor", 0.5);
        target = create("combat_target", scenario >= 6 && scenario <= 8 || scenario == 10 ? 3.1 : 12.5);
        target.player().getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        target.player().setHealth(200);
        previousTargetHealth = target.player().getHealth();
        report("START " + CASES[scenario]);
        try {
            switch (scenario) {
                case 0 -> fight = actor.combat().fight(target.player(), policy());
                case 1 -> fight = actor.combat().fight(target.player(), policy().swordOnly());
                case 2 -> {
                    actor.player().getInventory().setItem(2, ItemStack.EMPTY);
                    fight = actor.combat().fight(target.player(), policy());
                }
                case 3 -> {
                    actor.player().setHealth(4);
                    fight = actor.combat().fight(target.player(), policy().withTactics(
                            CombatTactics.standard().withLimits(200, 100, 5)));
                }
                case 4 -> {
                    for (int z=-6;z<=6;z++) for (int y=0;y<=5;y++) block(4,y,z,Blocks.BEDROCK);
                    fight = actor.combat().fight(target.player(), policy().withTactics(
                            CombatTactics.standard().withLimits(200, 12, 0)));
                }
                case 5 -> fight = actor.combat().fight(target.player(), policy().swordOnly().withTactics(
                        CombatTactics.standard().withLimits(12, 12, 0)));
                case 6 -> {
                    bystander = create("combat_blocker", 1.6);
                    for (int x=-2;x<=8;x++) for (int y=0;y<=3;y++) {
                        block(x,y,-1,Blocks.BEDROCK); block(x,y,1,Blocks.BEDROCK);
                    }
                    fight = actor.combat().fight(target.player(), policy().swordOnly());
                    // Check the real server entities, collision shapes and reach
                    // immediately before movement can change the fixture.
                    var ray = actor.combat().getClass().getDeclaredMethod("rayHitsTarget");
                    ray.setAccessible(true);
                    actor.actions().look(-90, 0);
                    boolean blocked = !(boolean) ray.invoke(actor.combat());
                    bystander.remove();
                    boolean unblocked = (boolean) ray.invoke(actor.combat());
                    target.player().setPos(point(4.0));
                    boolean beyondReach = !(boolean) ray.invoke(actor.combat());
                    finish(blocked && unblocked && beyondReach,
                            "blocked="+blocked+" clear="+unblocked+" reach="+beyondReach);
                }
                case 7 -> {
                    for (int x=-6;x<0;x++) for (int z=-6;z<=6;z++) {
                        for (int y=-8;y<0;y++) block(x,y,z,Blocks.AIR);
                    }
                    for (int x=-1;x<=8;x++) for (int y=0;y<=3;y++) {
                        block(x,y,-1,Blocks.BEDROCK); block(x,y,1,Blocks.BEDROCK);
                    }
                    fight = actor.combat().fight(target.player(), policy().bowOnly());
                }
                case 8 -> {
                    target.actions().look(90,0);
                    target.player().setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                    target.player().startUsingItem(InteractionHand.OFF_HAND);
                    actor.player().setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                    fight = actor.combat().fight(target.player(), policy());
                }
                case 9 -> {
                    fight = actor.combat().fight(target.player(), policy().withTactics(
                            CombatTactics.standard().withLimits(5, 5, 0)));
                    fight.completion().thenRun(() -> {
                        finalSnapshot = fight.diagnostics();
                        callbackFight = actor.combat().fight(target.player(), policy().swordOnly());
                    });
                }
                case 10 -> {
                    target.player().setInvulnerable(false);
                    for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                            EquipmentSlot.LEGS, EquipmentSlot.FEET)) target.player().setItemSlot(slot, ItemStack.EMPTY);
                    fight = actor.combat().fight(target.player(), policy().swordOnly());
                }
            }
        } catch (Exception error) { finish(false, "setup="+error); }
    }

    private void tick() {
        try {
            ticks++;
            CombatDiagnostics d = fight.diagnostics();
            switch (scenario) {
                case 0 -> {
                    if (!rangedShot && d.shots() > 0) {
                        rangedShot = d.weapon() == CombatWeapon.BOW
                                && actor.player().getInventory().getItem(2).getCount() < 64;
                        target.player().setPos(actor.player().position().add(2.5,0,0));
                        target.player().setDeltaMovement(Vec3.ZERO);
                        switched = true;
                    }
                    if (switched && d.weapon() == CombatWeapon.SWORD && d.attacks() > 0)
                        finish(rangedShot, "vanilla shot + melee " + d);
                }
                case 1,2 -> { if (ticks==2) finish(d.weapon()==CombatWeapon.SWORD && d.shots()==0, d.toString()); }
                case 3 -> { if (fight.completion().isDone()) finish(reason("health_limit")
                        && actor.player().zza==0 && actor.player().xxa==0 && !actor.player().isUsingItem(), d.toString()); }
                case 4 -> { if (fight.completion().isDone()) finish(reason("target_not_visible")
                        && d.elapsedTicks()==12 && d.attacks()==0 && d.shots()==0, d.toString()); }
                case 5 -> { if (fight.completion().isDone()) finish(reason("combat_timeout")
                        && d.elapsedTicks()==12, d.toString()); }
                case 7 -> {
                    if (actor.player().getY()<199.9 || actor.player().getX()<12000.29) {
                        finish(false,"walked over ledge pos="+actor.player().position()); return;
                    }
                    if (ticks>=60) finish(d.blockedMovementTicks()>10 && actor.player().getHealth()==20, d.toString());
                }
                case 8 -> {
                    axeSeen |= d.weapon()==CombatWeapon.AXE;
                    shieldSeen |= d.blocking() && actor.player().isUsingItem();
                    boolean disabled = target.player().getCooldowns().isOnCooldown(target.player().getOffhandItem());
                    // A freshly connected ServerPlayer can ignore early hits.
                    // Observe an accepted axe hit after normal login immunity;
                    // the finite deadline still fails if the shield never breaks.
                    if (axeSeen && shieldSeen && d.attacks()>0 && disabled)
                        finish(true,"vanilla shield/axe targetShieldDisabled=true " + d);
                    else if (ticks>=200) finish(false,"shield_not_disabled axeSeen="+axeSeen
                            +" shieldSeen="+shieldSeen+" "+d);
                }
                case 9 -> {
                    if (callbackFight != null && ticks>=8) finish(fight.status()==CombatStatus.FAILED
                            && callbackFight.status()==CombatStatus.RUNNING && fight.diagnostics().equals(finalSnapshot)
                            && fight.diagnostics().elapsedTicks()==5 && actor.combat().task()==callbackFight,
                            "old="+fight.diagnostics()+" new="+callbackFight.diagnostics());
                }
                case 10 -> {
                    float now = target.player().getHealth();
                    float damage = previousTargetHealth - now;
                    // Login immunity is left to expire normally. Once vanilla
                    // accepts the first hit, a charged diamond sword must do
                    // weapon damage rather than the tiny cooldown penalty.
                    if (damage > 0) finish(damage >= 6.0f && d.attacks() > 0,
                            "firstAcceptedDamage="+damage+" attacks="+d.attacks()+" ticks="+ticks);
                    previousTargetHealth = now;
                }
            }
            if (active==this && ticks>250) finish(false,"timeout "+d);
        } catch (Exception error) { finish(false,"tick="+error); }
    }

    private boolean reason(String expected) {
        return fight.status()==CombatStatus.FAILED && expected.equals(fight.completion().getNow(null).reason());
    }

    private void finish(boolean ok, String detail) {
        if (!ok) failures++;
        report((ok ? "OK " : "ERROR ") + CASES[scenario] + " " + detail);
        next();
    }
}
