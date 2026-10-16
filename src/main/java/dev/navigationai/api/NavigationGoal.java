package dev.navigationai.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Objects;

public sealed interface NavigationGoal permits NavigationGoal.BlockGoal, NavigationGoal.PositionGoal,
        NavigationGoal.FollowingGoal, NavigationGoal.LookAtGoal, NavigationGoal.ReturnGoal,
        NavigationGoal.PatrolGoal, NavigationGoal.MultiGoal,
        NavigationGoal.DimensionGoal {
    record BlockGoal(BlockPos position, double tolerance) implements NavigationGoal {
        public BlockGoal {
            Objects.requireNonNull(position, "position");
            validateTolerance(tolerance);
        }
        public BlockGoal(BlockPos position) { this(position, 1.0); }
    }
    record PositionGoal(Vec3 position, double tolerance) implements NavigationGoal {
        public PositionGoal {
            validatePosition(position);
            validateTolerance(tolerance);
        }
        public PositionGoal(Vec3 position) { this(position, 0.75); }
    }
    sealed interface FollowingGoal extends NavigationGoal permits FollowGoal, EscortGoal, EntityFollowGoal, EntityEscortGoal {
        net.minecraft.world.entity.LivingEntity target();
        double distance();
        double repathDistance();
    }

    record FollowGoal(ServerPlayer target, double distance, double repathDistance) implements FollowingGoal {
        public FollowGoal {
            Objects.requireNonNull(target, "target");
            validateFinite("distance", distance);
            validateFinite("repathDistance", repathDistance);
            if (distance < 0.5) throw new IllegalArgumentException("distance must be >= 0.5");
            if (repathDistance < distance) throw new IllegalArgumentException("repathDistance must be >= distance");
        }
        public FollowGoal(ServerPlayer target, double distance) { this(target, distance, 4.0); }
    }
    record LookAtGoal(Vec3 position, double tolerance) implements NavigationGoal {
        public LookAtGoal {
            validatePosition(position);
            validateTolerance(tolerance);
        }
        public LookAtGoal(Vec3 position) { this(position, 0.75); }
    }
    record ReturnGoal(Vec3 position, double tolerance) implements NavigationGoal {
        public ReturnGoal {
            validatePosition(position);
            validateTolerance(tolerance);
        }
        public ReturnGoal(Vec3 position) { this(position, 0.75); }
    }
    record PatrolGoal(List<Vec3> waypoints, boolean loop, double tolerance) implements NavigationGoal {
        public PatrolGoal {
            Objects.requireNonNull(waypoints, "waypoints");
            if (waypoints.isEmpty()) throw new IllegalArgumentException("waypoints cannot be empty");
            waypoints.forEach(NavigationGoal::validatePosition);
            waypoints = List.copyOf(waypoints);
            validateTolerance(tolerance);
        }
        public PatrolGoal(List<Vec3> waypoints, boolean loop) { this(waypoints, loop, 0.75); }
    }
    record EscortGoal(ServerPlayer target, double distance, double repathDistance) implements FollowingGoal {
        public EscortGoal {
            Objects.requireNonNull(target, "target");
            validateFinite("distance", distance);
            validateFinite("repathDistance", repathDistance);
            if (distance < 0.5) throw new IllegalArgumentException("distance must be >= 0.5");
            if (repathDistance < distance) throw new IllegalArgumentException("repathDistance must be >= distance");
        }
        public EscortGoal(ServerPlayer target, double distance) { this(target, distance, 4.0); }
    }
    /** Follow any loaded living entity, including another controlled villager. */
    record EntityFollowGoal(net.minecraft.world.entity.LivingEntity target, double distance, double repathDistance) implements FollowingGoal {
        public EntityFollowGoal { validateFollowing(target, distance, repathDistance); }
        public EntityFollowGoal(net.minecraft.world.entity.LivingEntity target, double distance) { this(target, distance, Math.max(4, distance)); }
    }
    record EntityEscortGoal(net.minecraft.world.entity.LivingEntity target, double distance, double repathDistance) implements FollowingGoal {
        public EntityEscortGoal { validateFollowing(target, distance, repathDistance); }
        public EntityEscortGoal(net.minecraft.world.entity.LivingEntity target, double distance) { this(target, distance, Math.max(4, distance)); }
    }
    private static void validateFollowing(net.minecraft.world.entity.LivingEntity target, double distance, double repath) {
        Objects.requireNonNull(target, "target"); validateFinite("distance", distance); validateFinite("repathDistance", repath);
        if (distance < .5 || repath < distance) throw new IllegalArgumentException("distance must be >= .5 and repathDistance >= distance");
    }
    record MultiGoal(List<NavigationGoal> goals) implements NavigationGoal {
        public MultiGoal {
            Objects.requireNonNull(goals, "goals");
            if (goals.isEmpty()) throw new IllegalArgumentException("goals cannot be empty");
            if (goals.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("goals cannot contain null");
            goals = List.copyOf(goals);
        }
    }
    record DimensionGoal(ResourceKey<Level> dimension, Vec3 position, double tolerance) implements NavigationGoal {
        public DimensionGoal {
            Objects.requireNonNull(dimension, "dimension");
            validatePosition(position);
            validateTolerance(tolerance);
        }
        public DimensionGoal(ResourceKey<Level> dimension, Vec3 position) { this(dimension, position, 0.75); }
    }

    private static void validatePosition(Vec3 position) {
        Objects.requireNonNull(position, "position");
        if (!Double.isFinite(position.x()) || !Double.isFinite(position.y()) || !Double.isFinite(position.z())) {
            throw new IllegalArgumentException("position coordinates must be finite");
        }
    }

    private static void validateTolerance(double tolerance) {
        validateFinite("tolerance", tolerance);
        if (tolerance < 0.0) throw new IllegalArgumentException("tolerance cannot be negative");
    }

    private static void validateFinite(String name, double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }
}
