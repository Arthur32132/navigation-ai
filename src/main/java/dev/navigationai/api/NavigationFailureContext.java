package dev.navigationai.api;

import java.util.List;
import net.minecraft.core.BlockPos;

/** The last failed movement or action, when a navigation task ends as STUCK. */
public record NavigationFailureContext(String reason, BlockPos from, BlockPos to, List<String> actions) {
    public NavigationFailureContext {
        actions = List.copyOf(actions);
    }
}
