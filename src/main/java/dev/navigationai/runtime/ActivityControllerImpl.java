package dev.navigationai.runtime;

import dev.navigationai.api.*;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.core.registries.BuiltInRegistries;

/** Keeps intent and collected inventory across interruptions, never an obsolete path. */
final class ActivityControllerImpl implements ActivityController {
    private final BotRuntime runtime;
    private final BotHandleImpl owner;
    private ActivityTaskImpl task;

    ActivityControllerImpl(BotRuntime runtime, BotHandleImpl owner) { this.runtime = runtime; this.owner = owner; }

    @Override public ActivityTask run(List<BotActivity> steps) {
        runtime.ensureServerThread(); owner.ensureActive();
        List<BotActivity> plan = List.copyOf(Objects.requireNonNull(steps));
        if (plan.isEmpty() || plan.size() > 1024) throw new IllegalArgumentException("steps must contain 1..1024 activities");
        long claim = owner.control().claim(BotControl.Kind.ACTIVITY);
        if (claim >= 0) cancel();
        ActivityTaskImpl next = new ActivityTaskImpl(plan);
        if (claim < 0 || !owner.control().current(claim)) { next.finish(ActivityStatus.CANCELLED, "superseded"); return next; }
        owner.ensureActive();
        task = next;
        return next;
    }

    @Override public CombatTask interruptForCombat(LivingEntity target, CombatPolicy policy, int timeoutTicks) {
        runtime.ensureServerThread(); owner.ensureActive();
        Objects.requireNonNull(target); Objects.requireNonNull(policy);
        if (timeoutTicks < 1 || timeoutTicks > 72000) throw new IllegalArgumentException("timeoutTicks must be 1..72000");
        if (target == owner.entity() || target instanceof net.minecraft.world.entity.player.Player && !policy.allowPlayers()
                || !(target instanceof net.minecraft.world.entity.player.Player)
                && (!(target instanceof net.minecraft.world.entity.Mob) || !policy.allowMobs())) {
            throw new IllegalArgumentException("target type is not allowed by policy");
        }
        ActivityTaskImpl current = task;
        if (current == null || current.status != ActivityStatus.RUNNING) throw new IllegalStateException("No running activity to interrupt");
        current.status = ActivityStatus.PAUSED;
        current.interruptions++;
        long generation = owner.control().generation();
        current.cancelStep();
        if (task != current || current.status != ActivityStatus.PAUSED || !owner.control().current(generation)) {
            throw new IllegalStateException("Activity was superseded during interruption");
        }
        current.combatTicks = 0;
        current.combatTimeout = timeoutTicks;
        current.combatTarget = target;
        current.combatPolicy = policy;
        current.combat = owner.combatImpl().fightAsChild(target, policy);
        return current.combat;
    }

    @Override public ActivityTask task() { return task; }
    @Override public ActivityStatus status() { return task == null ? ActivityStatus.IDLE : task.status; }
    @Override public void cancel() {
        runtime.ensureServerThread();
        if (task != null) task.cancel();
    }

    void shutdown() {
        if (task == null || task.status != ActivityStatus.RUNNING && task.status != ActivityStatus.PAUSED) return;
        task.cancelStep();
        if (task.combat != null && task.combat.status() == CombatStatus.RUNNING) task.combat.cancel();
    }

    void saveState(ValueOutput output) {
        ActivityTaskImpl current = task;
        boolean active = current != null && (current.status == ActivityStatus.RUNNING || current.status == ActivityStatus.PAUSED);
        output.putBoolean("active", active);
        if (!active) return;
        current.syncExcavationProgress();
        output.putInt("version", 1); output.putInt("index", current.index);
        output.putInt("baseline", current.baseline); output.putInt("stepTicks", current.stepTicks);
        output.putBoolean("initialized", current.initialized); output.putInt("interruptions", current.interruptions);
        output.putInt("excavationSegments", current.excavationSegments); output.putInt("excavationBlocks", current.excavationBlocks);
        output.putBoolean("mineCompleted", current.mineCompleted);
        if (current.initialBlock != null) output.store("initialBlock", BlockState.CODEC, current.initialBlock);
        var steps = output.childrenList("steps");
        for (BotActivity step : current.plan) {
            ValueOutput entry = steps.addChild();
            if (step instanceof BotActivity.Navigate nav) {
                entry.putString("type", "navigate");
                owner.navigatorImpl().saveGoal(entry.child("goal"), nav.goal());
                NavigatorImpl.writePolicy(entry.child("policy"), nav.policy());
            } else if (step instanceof BotActivity.Mine mine) {
                entry.putString("type", "mine"); entry.store("position", net.minecraft.core.BlockPos.CODEC, mine.position());
            } else if (step instanceof BotActivity.Wait wait) {
                entry.putString("type", "wait"); entry.putInt("ticks", wait.ticks());
            } else if (step instanceof BotActivity.Excavate dig) {
                entry.putString("type", "excavate");
                ExcavationRequest request = dig.request(); ExcavationPolicy policy = dig.policy();
                entry.store("origin", net.minecraft.core.BlockPos.CODEC, request.origin());
                entry.putString("direction", request.direction().name()); entry.putString("mode", request.mode().name());
                entry.putInt("length", request.length()); entry.putInt("completed", request.completedSegments());
                entry.putInt("maxBlocks", policy.maxBlocks()); entry.putInt("timeoutTicks", policy.timeoutTicks());
                entry.putInt("durability", policy.minRemainingDurability());
                entry.putBoolean("collectDrops", policy.collectDrops()); entry.putBoolean("return", policy.returnToEntrance());
            } else if (step instanceof BotActivity.Gather gather) {
                entry.putString("type", "gather"); entry.putInt("count", gather.request().count());
                entry.putInt("radius", gather.policy().radius()); entry.putInt("timeoutTicks", gather.policy().timeoutTicks());
                entry.putInt("attempts", gather.policy().maxAttemptsPerBlock());
                NavigatorImpl.writePolicy(entry.child("policy"), gather.navigationPolicy());
                var blocks = entry.childrenList("blocks");
                for (var block : gather.request().blocks()) blocks.addChild().store("id", BuiltInRegistries.BLOCK.byNameCodec(), block);
                var items = entry.childrenList("items");
                for (var item : gather.request().items()) items.addChild().store("id", BuiltInRegistries.ITEM.byNameCodec(), item);
            }
        }
        boolean paused = current.status == ActivityStatus.PAUSED && current.combatTarget != null && current.combatTarget.isAlive();
        output.putBoolean("paused", paused);
        if (paused) {
            var fight = output.child("combat");
            fight.store("target", net.minecraft.core.UUIDUtil.CODEC, current.combatTarget.getUUID());
            fight.putInt("timeoutTicks", Math.max(1, current.combatTimeout - current.combatTicks));
            CombatPolicy policy = current.combatPolicy;
            fight.putString("skill", policy.skill().name()); fight.putString("weapon", policy.weaponMode().name());
            fight.putDouble("distance", policy.maxFollowDistance()); fight.putDouble("melee", policy.meleeRange()); fight.putDouble("bow", policy.bowRange());
            fight.putBoolean("allowBow", policy.allowBow()); fight.putBoolean("mobs", policy.allowMobs()); fight.putBoolean("players", policy.allowPlayers());
            CombatTactics tactics = policy.tactics(); ValueOutput tactical = fight.child("tactics");
            tactical.putDouble("bowEnter", tactics.bowEnterDistance()); tactical.putDouble("bowExit", tactics.bowExitDistance());
            tactical.putInt("switchCooldown", tactics.weaponSwitchCooldownTicks()); tactical.putInt("duration", tactics.maxDurationTicks());
            tactical.putInt("unseen", tactics.maxUnseenTicks()); tactical.putFloat("health", tactics.minimumHealth());
            tactical.putBoolean("safe", tactics.safeMovement()); tactical.putBoolean("shield", tactics.useShield()); tactical.putBoolean("breakShields", tactics.breakShields());
        }
    }

    void restoreState(ValueInput input) {
        if (!input.getBooleanOr("active", false)) return;
        if (input.getIntOr("version", 0) != 1) throw new IllegalArgumentException("Unsupported activity save version");
        List<BotActivity> plan = input.childrenListOrEmpty("steps").stream().map(entry -> {
            String type = entry.getStringOr("type", "");
            return switch (type) {
                case "navigate" -> (BotActivity)new BotActivity.Navigate(owner.navigatorImpl().readGoal(entry.childOrEmpty("goal")), NavigatorImpl.restorePolicy(entry.childOrEmpty("policy")));
                case "mine" -> new BotActivity.Mine(entry.read("position", net.minecraft.core.BlockPos.CODEC).orElseThrow());
                case "wait" -> new BotActivity.Wait(entry.getIntOr("ticks", 0));
                case "excavate" -> new BotActivity.Excavate(
                        new ExcavationRequest(entry.read("origin", net.minecraft.core.BlockPos.CODEC).orElseThrow(),
                                net.minecraft.core.Direction.valueOf(entry.getStringOr("direction", "")),
                                ExcavationMode.valueOf(entry.getStringOr("mode", "")), entry.getIntOr("length", 0), entry.getIntOr("completed", 0)),
                        new ExcavationPolicy(entry.getIntOr("maxBlocks", 0), entry.getIntOr("timeoutTicks", 0), entry.getIntOr("durability", 0),
                                entry.getBooleanOr("collectDrops", true), entry.getBooleanOr("return", true)));
                case "gather" -> {
                    var blocks = entry.childrenListOrEmpty("blocks").stream().map(value -> value.read("id", BuiltInRegistries.BLOCK.byNameCodec()).orElseThrow()).collect(java.util.stream.Collectors.toSet());
                    var items = entry.childrenListOrEmpty("items").stream().map(value -> value.read("id", BuiltInRegistries.ITEM.byNameCodec()).orElseThrow()).collect(java.util.stream.Collectors.toSet());
                    yield new BotActivity.Gather(new GatherRequest(blocks, items, entry.getIntOr("count", 0)),
                            new GatherPolicy(entry.getIntOr("radius", 0), entry.getIntOr("timeoutTicks", 0), entry.getIntOr("attempts", 0)),
                            NavigatorImpl.restorePolicy(entry.childOrEmpty("policy")));
                }
                default -> throw new IllegalArgumentException("Unsupported activity type: " + type);
            };
        }).toList();
        int index = input.getIntOr("index", -1);
        if (index < 0 || index >= plan.size()) throw new IllegalArgumentException("Invalid saved activity index");
        ActivityTask restored = run(plan);
        if (restored.status() != ActivityStatus.RUNNING) return;
        ActivityTaskImpl current = task;
        current.index = index; current.baseline = input.getIntOr("baseline", 0);
        current.stepTicks = Math.max(0, input.getIntOr("stepTicks", 0));
        current.initialized = input.getBooleanOr("initialized", false);
        current.interruptions = Math.max(0, input.getIntOr("interruptions", 0));
        current.excavationSegments = Math.max(0, input.getIntOr("excavationSegments", 0));
        current.excavationBlocks = Math.max(0, input.getIntOr("excavationBlocks", 0));
        current.mineCompleted = input.getBooleanOr("mineCompleted", false);
        current.initialBlock = input.read("initialBlock", BlockState.CODEC).orElse(null);
        if (input.getBooleanOr("paused", false)) {
            var fight = input.childOrEmpty("combat");
            var entity = ((net.minecraft.server.level.ServerLevel)owner.entity().level()).getEntity(fight.read("target", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
            if (!(entity instanceof LivingEntity target) || !target.isAlive()) { current.finish(ActivityStatus.FAILED, "restored_combat_target_unavailable"); return; }
            CombatPolicy policy = new CombatPolicy(CombatSkill.valueOf(fight.getStringOr("skill", "STANDARD")), fight.getDoubleOr("distance", 32),
                    fight.getDoubleOr("melee", 3), fight.getDoubleOr("bow", 28), fight.getBooleanOr("allowBow", true),
                    fight.getBooleanOr("mobs", true), fight.getBooleanOr("players", true), CombatWeaponMode.valueOf(fight.getStringOr("weapon", "AUTO")),
                    restoreTactics(fight.childOrEmpty("tactics")));
            interruptForCombat(target, policy, fight.getIntOr("timeoutTicks", 1));
            current.interruptions = Math.max(0, input.getIntOr("interruptions", 0));
        }
    }

    private static CombatTactics restoreTactics(ValueInput input) {
        CombatTactics defaults = CombatTactics.standard();
        return new CombatTactics(input.getDoubleOr("bowEnter", defaults.bowEnterDistance()), input.getDoubleOr("bowExit", defaults.bowExitDistance()),
                input.getIntOr("switchCooldown", defaults.weaponSwitchCooldownTicks()), input.getIntOr("duration", defaults.maxDurationTicks()),
                input.getIntOr("unseen", defaults.maxUnseenTicks()), input.getFloatOr("health", defaults.minimumHealth()),
                input.getBooleanOr("safe", defaults.safeMovement()), input.getBooleanOr("shield", defaults.useShield()), input.getBooleanOr("breakShields", defaults.breakShields()));
    }

    void tick() {
        ActivityTaskImpl current = task;
        if (current == null) return;
        if (current.status == ActivityStatus.PAUSED) {
            if (current.combat == null) { current.finish(ActivityStatus.FAILED, "combat_not_started"); return; }
            if (current.combat.status() == CombatStatus.RUNNING) {
                if (++current.combatTicks > current.combatTimeout) {
                    current.finish(ActivityStatus.FAILED, "combat_timeout");
                }
                return;
            }
            CombatResult result = current.combat.completion().getNow(null);
            current.combat = null;
            current.combatTarget = null;
            if (result == null || result.status() != CombatStatus.SUCCESS) {
                current.finish(ActivityStatus.FAILED, "combat_" + (result == null ? "cancelled" : result.reason()));
                return;
            }
            current.status = ActivityStatus.RUNNING;
        }
        if (current.status != ActivityStatus.RUNNING) return;
        current.stepTicks++;
        BotActivity step = current.plan.get(current.index);
        if (step instanceof BotActivity.Gather gather && current.stepTicks > gather.policy().timeoutTicks()) {
            current.finish(ActivityStatus.FAILED, "gather_timeout"); return;
        }
        if (step instanceof BotActivity.Excavate dig && current.stepTicks > dig.policy().timeoutTicks()) {
            current.finish(ActivityStatus.FAILED, "excavation_timeout"); return;
        }
        try {
            if (!current.childStarted) current.startStep(step);
            if (task != current || current.status != ActivityStatus.RUNNING) return;
            String outcome = current.stepOutcome();
            if (outcome == null) return;
            if (!outcome.equals("success")) { current.finish(ActivityStatus.FAILED, outcome); return; }
            current.navigation = null; current.gathering = null; current.mining = null; current.excavation = null;
            current.childStarted = false; current.initialized = false; current.stepTicks = 0;
            current.excavationSegments = 0; current.excavationBlocks = 0; current.mineCompleted = false; current.initialBlock = null;
            if (++current.index == current.plan.size()) current.finish(ActivityStatus.SUCCESS, "completed");
        } catch (RuntimeException failure) {
            if (task == current) current.finish(ActivityStatus.FAILED, "step_exception:" + failure.getClass().getSimpleName());
        }
    }

    private int inventoryCount(GatherRequest request) {
        int count = 0;
        for (ItemStack stack : ActorSupport.context(owner.entity()).getInventory().getNonEquipmentItems()) {
            if (request.items().contains(stack.getItem())) count += stack.getCount();
        }
        return count;
    }

    private final class ActivityTaskImpl implements ActivityTask {
        final List<BotActivity> plan;
        final CompletableFuture<ActivityResult> completion = new CompletableFuture<>();
        final long started = System.nanoTime();
        ActivityStatus status = ActivityStatus.RUNNING;
        int index, interruptions, baseline, stepTicks, combatTicks, combatTimeout;
        int excavationSegments, excavationBlocks, excavationChildBaseline;
        boolean initialized, childStarted;
        boolean mineCompleted;
        BlockState initialBlock;
        NavigationTask navigation;
        GatherTask gathering;
        MiningTask mining;
        ExcavationTask excavation;
        CombatTask combat;
        LivingEntity combatTarget;
        CombatPolicy combatPolicy;
        ActivityTaskImpl(List<BotActivity> plan) { this.plan = plan; }
        @Override public ActivityStatus status() { return status; }
        @Override public int stepIndex() { return index; }
        @Override public CompletableFuture<ActivityResult> completion() { return completion.copy(); }
        @Override public void cancel() { runtime.ensureServerThread(); finish(ActivityStatus.CANCELLED, "cancelled"); }

        void startStep(BotActivity step) {
            childStarted = true;
            if (step instanceof BotActivity.Navigate navigate) {
                navigation = owner.navigatorImpl().goToAsChild(navigate.goal(), navigate.policy());
            } else if (step instanceof BotActivity.Gather gather) {
                if (!initialized) baseline = inventoryCount(gather.request());
                int remaining = gather.request().count() - Math.max(0, inventoryCount(gather.request()) - baseline);
                initialized = true;
                if (remaining > 0) {
                    gathering = owner.collectorImpl().gatherAsChild(new GatherRequest(gather.request().blocks(),
                                    gather.request().items(), remaining),
                            new GatherPolicy(gather.policy().radius(), Math.max(1, gather.policy().timeoutTicks() - stepTicks),
                                    gather.policy().maxAttemptsPerBlock()), gather.navigationPolicy());
                }
            } else if (step instanceof BotActivity.Mine mine) {
                if (mineCompleted) return;
                BlockState state = owner.entity().level().getBlockState(mine.position());
                if (!initialized) initialBlock = state;
                initialized = true;
                if (!state.equals(initialBlock)) { finish(ActivityStatus.FAILED, "mining_block_changed"); return; }
                mining = owner.minerImpl().breakBlockAsChild(mine.position());
            } else if (step instanceof BotActivity.Excavate dig) {
                if (!initialized) excavationSegments = dig.request().completedSegments();
                initialized = true;
                if (excavationSegments > dig.request().length() || excavationBlocks > dig.policy().maxBlocks()) {
                    finish(ActivityStatus.FAILED, "invalid_excavation_progress"); return;
                }
                int remainingBlocks = dig.policy().maxBlocks() - excavationBlocks;
                excavationChildBaseline = excavationBlocks;
                excavation = owner.minerImpl().excavateAsChild(dig.request().withCompletedSegments(excavationSegments),
                        new ExcavationPolicy(remainingBlocks, Math.max(1, dig.policy().timeoutTicks() - stepTicks),
                                dig.policy().minRemainingDurability(), dig.policy().collectDrops(), dig.policy().returnToEntrance()));
            }
        }

        String stepOutcome() {
            if (plan.get(index) instanceof BotActivity.Wait wait) return stepTicks >= wait.ticks() ? "success" : null;
            if (excavation != null) {
                syncExcavationProgress();
                ExcavationResult result = excavation.completion().getNow(null);
                return result == null ? null : result.status() == ExcavationStatus.SUCCESS ? "success" : "excavation_" + result.reason();
            }
            if (navigation != null) {
                NavigationResult result = navigation.completion().getNow(null);
                return result == null ? null : result.status() == NavigationStatus.SUCCESS ? "success" : "navigation_" + result.reason();
            }
            if (gathering != null) {
                GatherResult result = gathering.completion().getNow(null);
                return result == null ? null : result.status() == GatherStatus.SUCCESS ? "success" : "gather_" + result.reason();
            }
            if (mining != null) {
                MiningResult result = mining.completion().getNow(null);
                return result == null ? null : result.status() == MiningStatus.SUCCESS ? "success" : "mining_" + result.reason();
            }
            return "success";
        }

        void cancelStep() {
            syncExcavationProgress();
            if (mining != null && mining.status() == MiningStatus.SUCCESS) mineCompleted = true;
            NavigationTask nav = navigation; GatherTask gather = gathering; MiningTask mine = mining;
            ExcavationTask dig = excavation;
            navigation = null; gathering = null; mining = null; excavation = null; childStarted = false;
            if (dig != null) dig.cancel();
            if (gather != null) gather.cancel();
            if (nav != null) nav.cancel();
            if (mine != null) mine.cancel();
        }

        void syncExcavationProgress() {
            if (excavation == null) return;
            excavationSegments = excavation.completedSegments();
            excavationBlocks = excavationChildBaseline + excavation.brokenBlocks();
        }

        void finish(ActivityStatus terminal, String reason) {
            if (status != ActivityStatus.RUNNING && status != ActivityStatus.PAUSED) return;
            status = terminal;
            CombatTask fight = combat; combat = null;
            cancelStep();
            if (fight != null) fight.cancel();
            completion.complete(new ActivityResult(terminal, reason, index, interruptions, System.nanoTime() - started));
        }
    }
}
