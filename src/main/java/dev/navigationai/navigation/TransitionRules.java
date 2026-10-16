package dev.navigationai.navigation;

import dev.navigationai.api.NavigationPolicy;
import java.util.ArrayList;

/** The same movement and pose rules are used by A* and live geometry validation. */
public final class TransitionRules {
    private TransitionRules() { }

    public static GridTransition describe(GridWorld world, GridPos from, GridPos to, NavigationPolicy policy) {
        if (from.equals(to) || !world.passable(from) || !world.passable(to)) return null;
        int dx = Math.abs(to.x() - from.x()), dz = Math.abs(to.z() - from.z());
        int dy = to.y() - from.y();
        boolean longJump = Math.max(dx, dz) > 1;
        // A diagonal target can be hidden behind the still solid cardinal corner. Dig cardinal faces first.
        if(world.requiresBreaking(to)&&dx+dz!=1)return null;
        boolean climb = world.isClimbable(from) || world.isClimbable(to);
        boolean swim = world.isWater(from) || world.isWater(to);
        if(!climb&&!swim&&world.supportHeight(to)-world.supportHeight(from)>1.25+1e-6)return null;
        boolean crouch = world.requiresSneak(from) || world.requiresSneak(to);
        // Buoyancy can climb a water column, but it cannot supply a grounded jump onto a tall dry bank.
        if (!climb && world.isWater(from) && !world.isWater(to)
                && world.supportHeight(to) - world.supportHeight(from) > .6) return null;
        if (dy < -policy.maxFallDistance() && !world.canDescendWithoutFalling(from, to)) return null;
        double descent = world.supportHeight(from) - world.supportHeight(to);
        if (descent > Math.max(.6, policy.maxFallDistance()) + 1e-6
                && !world.canDescendWithoutFalling(from, to)) return null;
        GridTransition.Type type;
        if (longJump) {
            if (!world.canJump(from, to) || crouch || climb || swim) return null;
            type = GridTransition.Type.JUMP;
        } else if (dy < -1) {
            if (!world.canDrop(from, to)) return null;
            type = GridTransition.Type.DROP;
        } else {
            if (!world.canTraverse(from, to)) return null;
            // Vertical air columns are not stairs. Only ladders/water support them.
            if (dx + dz == 0 && !climb && !swim) return null;
            type = climb ? GridTransition.Type.CLIMB : swim ? GridTransition.Type.SWIM
                    : world.supportHeight(to) - world.supportHeight(from) > 0.6
                    ? GridTransition.Type.JUMP : dy < 0 ? GridTransition.Type.DROP : GridTransition.Type.STEP;
            if (type == GridTransition.Type.JUMP && crouch) return null;
        }
        var actions = new ArrayList<GridTransition.Action>();
        if (world.requiresOpening(to)) actions.add(GridTransition.Action.OPEN_PASSAGE);
        if (world.requiresBreaking(to)) actions.add(GridTransition.Action.BREAK_BLOCK);
        if (world.requiresPlacement(to)) actions.add(GridTransition.Action.PLACE_SUPPORT);
        var pose = crouch ? GridTransition.RequiredPose.CROUCHING : type == GridTransition.Type.SWIM
                ? GridTransition.RequiredPose.SWIMMING : GridTransition.RequiredPose.STANDING;
        double distance = Math.hypot(dx, dz);
        // Sprint momentum is required only for the longer unsupported gaps.
        // Delta movement is observed after vanilla ground friction, rather than before travel.
        double speed = longJump && world.crossesGap(from, to) ? (distance > 3 ? 0.10 : 0.06) : 0;
        return new GridTransition(from, to, type, pose, world.supportHeight(from), world.supportHeight(to),
                new GridTransition.Takeoff(type == GridTransition.Type.JUMP, speed,
                        longJump && world.crossesGap(from, to) ? 0.25 : 0),
                new GridTransition.Landing(type == GridTransition.Type.JUMP || type == GridTransition.Type.DROP,
                        0.37, 0.65), actions);
    }

    public static boolean valid(GridWorld world, GridTransition transition, NavigationPolicy policy) {
        GridTransition current = describe(world, transition.from(), transition.to(), policy);
        return current != null && current.type() == transition.type()
                && current.pose() == transition.pose()
                && current.takeoff().equals(transition.takeoff())
                && current.landing().equals(transition.landing())
                && transition.actions().containsAll(current.actions())
                && Math.abs(current.fromSupportHeight() - transition.fromSupportHeight()) < 0.05
                && Math.abs(current.toSupportHeight() - transition.toSupportHeight()) < 0.05;
    }
}
