package dev.navigationai.api;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;

/** Server-thread action guards shared by mining and route planning. Any registered rule may veto. */
public final class BlockBreakPermissions {
    private static final CopyOnWriteArrayList<BiPredicate<UUID,BlockPos>> RULES=new CopyOnWriteArrayList<>();
    private BlockBreakPermissions() { }
    public static AutoCloseable register(BiPredicate<UUID,BlockPos> permission){
        Objects.requireNonNull(permission);RULES.add(permission);return ()->RULES.remove(permission);
    }
    public static boolean isAllowed(UUID actor,BlockPos position){return RULES.stream().allMatch(rule->rule.test(actor,position));}
}
