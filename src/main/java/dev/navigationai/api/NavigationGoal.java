package dev.navigationai.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

public sealed interface NavigationGoal permits NavigationGoal.BlockGoal, NavigationGoal.PositionGoal, NavigationGoal.FollowGoal {
    record BlockGoal(BlockPos position, double tolerance) implements NavigationGoal {
        public BlockGoal(BlockPos position) { this(position, 1.0); }
    }
    record PositionGoal(Vec3 position, double tolerance) implements NavigationGoal {
        public PositionGoal(Vec3 position) { this(position, 0.75); }
    }
    record FollowGoal(ServerPlayer target, double distance, double repathDistance) implements NavigationGoal {
        public FollowGoal(ServerPlayer target, double distance) { this(target, distance, 4.0); }
    }
}
