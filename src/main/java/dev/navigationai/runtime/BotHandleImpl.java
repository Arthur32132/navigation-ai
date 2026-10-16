package dev.navigationai.runtime;

import dev.navigationai.api.BotActions;
import dev.navigationai.api.ActivityController;
import dev.navigationai.api.BotHandle;
import dev.navigationai.api.BotState;
import dev.navigationai.api.CombatController;
import dev.navigationai.api.Miner;
import dev.navigationai.api.Navigator;
import dev.navigationai.api.ResourceCollector;
import dev.navigationai.api.BotInventory;
import dev.navigationai.api.BotSnapshot;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import dev.navigationai.api.BotKind;

final class BotHandleImpl implements BotHandle {
    private final BotRuntime runtime;
    private final ServerPlayer player;
    private LivingEntity entity;
    private VillagerController villagerController;
    private final String name;
    private final BotActionsImpl actions;
    private final NavigatorImpl navigator;
    private final MinerImpl miner;
    private final ResourceCollectorImpl collector;
    private final CombatControllerImpl combat;
    private final ActivityControllerImpl activities;
    private final BotInventoryImpl inventory;
    private final BotControl control = new BotControl();
    private BotState state = BotState.ACTIVE;

    BotHandleImpl(BotRuntime runtime, ServerPlayer player, String name) {
        this(runtime, player, player, name);
    }

    BotHandleImpl(BotRuntime runtime, ServerPlayer player, LivingEntity entity, String name) {
        this.runtime = runtime;
        this.player = player;
        this.entity = entity;
        this.name = name;
        this.actions = new BotActionsImpl(runtime, this);
        this.navigator = new NavigatorImpl(runtime, this);
        this.miner = new MinerImpl(runtime, this);
        this.collector = new ResourceCollectorImpl(runtime, this);
        this.combat = new CombatControllerImpl(runtime, this);
        this.activities = new ActivityControllerImpl(runtime, this);
        this.inventory = new BotInventoryImpl(runtime, this);
        control.register(BotControl.Kind.ACTIVITY, activities::cancel);
        control.register(BotControl.Kind.GATHER, collector::cancel);
        control.register(BotControl.Kind.NAVIGATION, navigator::cancel);
        control.register(BotControl.Kind.MINING, miner::cancel);
        control.register(BotControl.Kind.COMBAT, combat::cancel);
    }
    BotRuntime runtime() { return runtime; }
    @Override public UUID id() { return entity.getUUID(); }
    @Override public String name() { return name; }
    @Override public ServerPlayer player() {
        if (kind() != BotKind.PLAYER) throw new UnsupportedOperationException("Villager handles have entity(), not player(); use inventory() for items");
        return player;
    }
    @Override public LivingEntity entity() { return entity; }
    @Override public BotKind kind() { return entity instanceof Villager ? BotKind.VILLAGER : BotKind.PLAYER; }
    ServerPlayer context() { return player; }
    void markDetached() { state = BotState.REMOVED; }
    void retireForUnload() {
        state = BotState.REMOVED;
        navigator.retireForUnload();
        activities.cancel(); collector.cancel(); miner.cancel(); combat.cancel();
    }
    void rebindEntity(LivingEntity replacement) { entity = replacement; }
    void villagerController(VillagerController controller) { villagerController = controller; }
    void refreshEquipment() {
        if (villagerController != null) villagerController.refreshEquipment();
    }
    void movementInput(float strafe, float forward, boolean jump, boolean sprint, boolean sneak) {
        if (villagerController != null) villagerController.movementInput(strafe, forward, jump, sprint, sneak);
    }
    boolean controlsBody() {
        return navigator.status() == dev.navigationai.api.NavigationStatus.COMPUTING
                || navigator.status() == dev.navigationai.api.NavigationStatus.RUNNING
                || miner.status() == dev.navigationai.api.MiningStatus.RUNNING
                || miner.excavationStatus() == dev.navigationai.api.ExcavationStatus.RUNNING || combat.isActive()
                || collector.status() == dev.navigationai.api.GatherStatus.RUNNING
                || activities.status() == dev.navigationai.api.ActivityStatus.RUNNING
                || activities.status() == dev.navigationai.api.ActivityStatus.PAUSED || actions.manualMovement();
    }
    @Override public void detach() {
        runtime.ensureServerThread(); ensureActive();
        if (villagerController == null) throw new UnsupportedOperationException("Only villager handles can be detached");
        if (!stopAll()) return;
        state = BotState.REMOVED;
        runtime.detachVillager(this, false);
    }
    @Override public BotState state() { return state; }
    @Override public BotActions actions() { return actions; }
    @Override public Navigator navigator() { return navigator; }
    @Override public Miner miner() { return miner; }
    @Override public ResourceCollector collector() { return collector; }
    @Override public CombatController combat() { return combat; }
    @Override public BotInventory inventory() { return inventory; }
    @Override public BotSnapshot snapshot() {
        runtime.ensureServerThread();
        return new BotSnapshot(id(), name, state, entity.level().dimension().identifier().toString(),
                entity.position(), entity.getYRot(), entity.getXRot(), entity.getHealth(), kind() == BotKind.PLAYER ? player.getFoodData().getFoodLevel() : -1,
                navigator.status(), miner.status(), miner.excavationStatus(), collector.status(), combat.status(), activities.status(), inventory.snapshot());
    }
    @Override public boolean stopAll() {
        runtime.ensureServerThread(); ensureActive();
        long claim = control.claim(BotControl.Kind.MANUAL);
        if (claim < 0) return false;
        actions.stop();
        entity.stopUsingItem();
        return true;
    }
    @Override public ActivityController activities() { return activities; }
    BotControl control() { return control; }
    ResourceCollectorImpl collectorImpl() { return collector; }
    CombatControllerImpl combatImpl() { return combat; }
    ActivityControllerImpl activitiesImpl() { return activities; }
    MinerImpl minerImpl() { return miner; }
    void ensureActive() {
        runtime.ensureOpen();
        if (state != BotState.ACTIVE) throw new IllegalStateException("Bot is not active");
    }
    NavigatorImpl navigatorImpl() { return navigator; }
    @Override public void remove() {
        runtime.ensureServerThread();
        if (state == BotState.REMOVED) return;
        state = BotState.REMOVED;
        activities.cancel();
        navigator.cancel();
        collector.cancel();
        miner.cancel();
        combat.cancel();
        runtime.remove(this);
        navigator.tickPendingRollbacks();
    }
    void prepareForShutdown() {
        runtime.ensureServerThread();
        activities.shutdown();
        collector.cancel();
        miner.cancel();
        combat.cancel();
        navigator.shutdown();
    }
    void removeForShutdown() {
        runtime.remove(this);
        state = BotState.REMOVED;
    }
    void tick() {
        if (state == BotState.REMOVED) return;
        navigator.tickPendingRollbacks();
        if (entity.isDeadOrDying() || entity.isRemoved()) {
            state = BotState.DEAD;
            activities.cancel();
            navigator.cancel();
            miner.cancel();
            collector.cancel();
            combat.cancel();
            return;
        }
        // Vanilla sends a hit player's knockback to that player's client and
        // restores the old server velocity afterwards. Reapply the captured
        // packet so a fake player keeps the same server-side knockback.
        if (villagerController != null) villagerController.beforeTick();
        else runtime.applyPendingMotion(player);
        activities.tick();
        collector.tick();
        miner.tick();
        combat.tick();
        if (!combat.isActive()) navigator.tick();
        // A fake player has no network connection registered with ServerConnectionListener.
        // Run the same server-side simulation step that a real player's connection invokes.
        if (villagerController != null) villagerController.afterTick();
        else player.doTick();
    }
}
