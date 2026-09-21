package dev.navigationai.runtime;

import dev.navigationai.navigation.GridPos;
import dev.navigationai.navigation.GridWorld;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Immutable, server-thread captured collision snapshot for off-thread A*. */
final class WorldSnapshot implements GridWorld {
    private final Set<GridPos> passable;
    private final int minX, maxX, minY, maxY, minZ, maxZ;
    private final boolean water;

    private WorldSnapshot(Set<GridPos> passable, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, boolean water) {
        this.passable = Set.copyOf(passable); this.minX = minX; this.maxX = maxX; this.minY = minY; this.maxY = maxY; this.minZ = minZ; this.maxZ = maxZ; this.water = water;
    }

    static WorldSnapshot capture(ServerLevel level, GridPos start, GridPos goal, int radius, boolean allowWater) {
        // Keep the snapshot bounded by the requested radius. A distant goal is
        // intentionally outside this local snapshot and will be reported as
        // unreachable instead of making the server scan the whole rectangle.
        int minX = start.x() - radius, maxX = start.x() + radius;
        int minZ = start.z() - radius, maxZ = start.z() + radius;
        int minY = Math.max(level.getMinY(), Math.min(start.y(), goal.y()) - 8);
        int maxY = Math.min(level.getMinY() + level.getHeight() - 2, Math.max(start.y(), goal.y()) + 8);
        Set<GridPos> passable = new HashSet<>();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) for (int y = minY; y <= maxY; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.hasChunkAt(pos)) continue;
            boolean fluid = !level.getFluidState(pos).isEmpty();
            boolean body = level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir();
            boolean floor = !level.getBlockState(pos.below()).isAir() || (allowWater && fluid);
            if ((body && floor) || (allowWater && fluid && level.getBlockState(pos.above()).isAir())) passable.add(new GridPos(x, y, z));
        }
        return new WorldSnapshot(passable, minX, maxX, minY, maxY, minZ, maxZ, allowWater);
    }

    @Override public boolean passable(GridPos pos) {
        return pos.x() >= minX && pos.x() <= maxX && pos.y() >= minY && pos.y() <= maxY && pos.z() >= minZ && pos.z() <= maxZ && passable.contains(pos);
    }
    @Override public double cost(GridPos from, GridPos to) { return from.y() == to.y() ? 1.0 : 1.45; }
}
