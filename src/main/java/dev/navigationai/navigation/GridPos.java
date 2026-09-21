package dev.navigationai.navigation;

public record GridPos(int x, int y, int z) {
    public GridPos add(int dx, int dy, int dz) { return new GridPos(x + dx, y + dy, z + dz); }
    public int manhattan(GridPos other) { return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z); }
}
