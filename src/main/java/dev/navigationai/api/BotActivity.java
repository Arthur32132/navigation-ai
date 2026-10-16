package dev.navigationai.api;

import java.util.Objects;
import net.minecraft.core.BlockPos;

/** Immutable intent; a resumed step is revalidated against the current world. */
public sealed interface BotActivity {
    record Navigate(NavigationGoal goal, NavigationPolicy policy) implements BotActivity {
        public Navigate { Objects.requireNonNull(goal); Objects.requireNonNull(policy); }
    }
    record Gather(GatherRequest request, GatherPolicy policy, NavigationPolicy navigationPolicy) implements BotActivity {
        public Gather {
            Objects.requireNonNull(request); Objects.requireNonNull(policy); Objects.requireNonNull(navigationPolicy);
        }
    }
    record Mine(BlockPos position) implements BotActivity {
        public Mine { position = Objects.requireNonNull(position).immutable(); }
    }
    record Excavate(ExcavationRequest request, ExcavationPolicy policy) implements BotActivity {
        public Excavate { Objects.requireNonNull(request); Objects.requireNonNull(policy); }
    }
    /** Waits for server ticks; the elapsed portion survives interruptions and restarts. */
    record Wait(int ticks) implements BotActivity {
        public Wait {
            if (ticks < 1 || ticks > 72_000) throw new IllegalArgumentException("ticks must be 1..72000");
        }
    }
}
