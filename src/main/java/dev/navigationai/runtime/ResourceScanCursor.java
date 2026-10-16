package dev.navigationai.runtime;

import net.minecraft.core.BlockPos;

/** Visits concentric cube surfaces once, without allocating a radius-sized list. */
final class ResourceScanCursor {
    private final int radius;
    private int shell, index;
    ResourceScanCursor(int radius) { this.radius = radius; }
    boolean hasNext() { return shell <= radius; }
    BlockPos next(BlockPos origin) {
        if (!hasNext()) throw new java.util.NoSuchElementException();
        if (shell == 0) { shell = 1; return origin.immutable(); }
        int r = shell, side = r * 2 + 1, inner = side - 2;
        int face = side * side, strip = inner * side, inset = inner * inner;
        int cell = index++;
        int x, y, z;
        if (cell < 2 * face) {
            y = cell < face ? -r : r;
            int offset = cell % face;
            x = offset / side - r; z = offset % side - r;
        } else if ((cell -= 2 * face) < 2 * strip) {
            x = cell < strip ? -r : r;
            int offset = cell % strip;
            y = offset / side - r + 1; z = offset % side - r;
        } else {
            cell -= 2 * strip;
            z = cell < inset ? -r : r;
            int offset = cell % inset;
            x = offset / inner - r + 1; y = offset % inner - r + 1;
        }
        if (index == 2 * face + 2 * strip + 2 * inset) { shell++; index = 0; }
        return origin.offset(x, y, z);
    }
}
