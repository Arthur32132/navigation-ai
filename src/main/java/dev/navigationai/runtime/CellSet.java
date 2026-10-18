package dev.navigationai.runtime;

import dev.navigationai.navigation.GridPos;
import java.util.*;

/**
 * Set of grid cells backed by a bit per cell of a fixed box; cells outside the box go to a small
 * overflow set. A snapshot asks these sets millions of times per search, so lookups must not hash.
 */
final class CellSet extends AbstractSet<GridPos> {
    final CellBox box;
    private final long[] bits;
    private final Set<GridPos> overflow = new HashSet<>();
    private int size;

    CellSet(CellBox box) { this.box = box; this.bits = new long[(box.volume() + 63) >>> 6]; }

    boolean contains(int x, int y, int z) {
        int i = box.index(x, y, z);
        return i >= 0 ? (bits[i >>> 6] & 1L << i) != 0 : !overflow.isEmpty() && overflow.contains(new GridPos(x, y, z));
    }
    @Override public boolean contains(Object o) { return o instanceof GridPos p && contains(p.x(), p.y(), p.z()); }
    @Override public boolean add(GridPos p) {
        int i = box.index(p.x(), p.y(), p.z());
        if (i < 0) { boolean added = overflow.add(p); if (added) size++; return added; }
        long mask = 1L << i;
        if ((bits[i >>> 6] & mask) != 0) return false;
        bits[i >>> 6] |= mask; size++; return true;
    }
    @Override public boolean remove(Object o) {
        if (!(o instanceof GridPos p)) return false;
        int i = box.index(p.x(), p.y(), p.z());
        if (i < 0) { boolean removed = overflow.remove(p); if (removed) size--; return removed; }
        long mask = 1L << i;
        if ((bits[i >>> 6] & mask) == 0) return false;
        bits[i >>> 6] &= ~mask; size--; return true;
    }
    @Override public int size() { return size; }
    @Override public void clear() { Arrays.fill(bits, 0); overflow.clear(); size = 0; }
    @Override public Iterator<GridPos> iterator() {
        List<GridPos> all = new ArrayList<>(size);
        for (int w = 0; w < bits.length; w++) {
            long word = bits[w];
            while (word != 0) { int b = Long.numberOfTrailingZeros(word); all.add(box.cell((w << 6) + b)); word &= word - 1; }
        }
        all.addAll(overflow);
        Iterator<GridPos> it = all.iterator();
        return new Iterator<>() {
            GridPos last;
            public boolean hasNext() { return it.hasNext(); }
            public GridPos next() { return last = it.next(); }
            public void remove() { CellSet.this.remove(last); }
        };
    }

    /** The cells of one snapshot: an x/y/z box indexed in a flat array. */
    record CellBox(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
        static CellBox of(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            return new CellBox(minX, minY, minZ, Math.max(0, maxX - minX + 1), Math.max(0, maxY - minY + 1), Math.max(0, maxZ - minZ + 1));
        }
        int volume() { return sizeX * sizeY * sizeZ; }
        int index(int x, int y, int z) {
            int dx = x - minX, dy = y - minY, dz = z - minZ;
            if (dx < 0 || dy < 0 || dz < 0 || dx >= sizeX || dy >= sizeY || dz >= sizeZ) return -1;
            return (dx * sizeZ + dz) * sizeY + dy;
        }
        GridPos cell(int index) {
            int dy = index % sizeY, rest = index / sizeY;
            return new GridPos(minX + rest / sizeZ, minY + dy, minZ + rest % sizeZ);
        }
    }
}
