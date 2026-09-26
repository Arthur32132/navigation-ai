package dev.navigationai.navigation;

import java.util.List;
import java.util.Objects;

/** Immutable movement contract produced by planning and checked before execution. */
public record GridTransition(GridPos from, GridPos to, Type type, RequiredPose pose,
                             double fromSupportHeight, double toSupportHeight,
                             Takeoff takeoff, Landing landing, List<Action> actions) {
    public enum Type { STEP, JUMP, DROP, CLIMB, SWIM }
    public enum RequiredPose { STANDING, CROUCHING, SWIMMING }
    public enum Action { OPEN_PASSAGE, BREAK_BLOCK, PLACE_SUPPORT }
    public record Takeoff(boolean grounded, double minimumSpeed, double edgeProgress) {
        public Takeoff {
            if (!Double.isFinite(minimumSpeed) || minimumSpeed < 0 || !Double.isFinite(edgeProgress)
                    || edgeProgress < 0 || edgeProgress > .5) throw new IllegalArgumentException("Invalid takeoff");
        }
    }
    public record Landing(boolean stable, double horizontalTolerance, double verticalTolerance) {
        public Landing {
            if (!Double.isFinite(horizontalTolerance) || horizontalTolerance <= 0
                    || !Double.isFinite(verticalTolerance) || verticalTolerance <= 0) {
                throw new IllegalArgumentException("Invalid landing");
            }
        }
    }

    public GridTransition {
        Objects.requireNonNull(from); Objects.requireNonNull(to); Objects.requireNonNull(type);
        Objects.requireNonNull(pose); Objects.requireNonNull(takeoff); Objects.requireNonNull(landing);
        actions = List.copyOf(actions);
        if (from.equals(to) || !Double.isFinite(fromSupportHeight) || !Double.isFinite(toSupportHeight)) {
            throw new IllegalArgumentException("Invalid transition endpoints");
        }
    }

    public double horizontalDistance() { return Math.hypot(to.x() - from.x(), to.z() - from.z()); }
    public boolean gapJump() { return type == Type.JUMP && horizontalDistance() >= 2; }
}
