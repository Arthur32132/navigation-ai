package dev.navigationai.navigation;

public record GridPos(int x, int y, int z) {
    /**
     * The record default (961x + 31y + z) gives the same hash to many cells of one snapshot, so the
     * search maps degrade into tree bins. Mix all three coordinates instead.
     */
    @Override public int hashCode() {
        // Packed like BlockPos.asLong (unique inside the world), then the MurmurHash3 finaliser.
        long h = ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL; h ^= h >>> 33; h *= 0xC4CEB9FE1A85EC53L; h ^= h >>> 33;
        return (int) (h ^ h >>> 32);
    }
    public GridPos add(int dx, int dy, int dz) { return new GridPos(x + dx, y + dy, z + dz); }
    public int manhattan(GridPos other) { return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z); }
}
