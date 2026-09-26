package dev.navigationai.runtime;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/** Chunk identities prevent reuse after unload/reload; neighbours cover connected collision shapes. */
record ChunkGeometryStamp(List<Entry> entries) {
    record Entry(LevelChunk chunk, long revision) { }
    ChunkGeometryStamp { entries = List.copyOf(entries); }

    static ChunkGeometryStamp read(ServerLevel level, int chunkX, int chunkZ) {
        var entries = new ArrayList<Entry>(9);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX + dx, chunkZ + dz);
            entries.add(new Entry(chunk, chunk instanceof GeometryRevision revision
                    ? revision.navigationAiGeometryRevision() : -1));
        }
        return new ChunkGeometryStamp(entries);
    }
    boolean current(ServerLevel level, int chunkX, int chunkZ) { return equals(read(level, chunkX, chunkZ)); }
}
