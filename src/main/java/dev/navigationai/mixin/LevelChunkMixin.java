package dev.navigationai.mixin;

import dev.navigationai.runtime.GeometryRevision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin implements GeometryRevision {
    @Unique private long navigationAiRevision;
    @Override public long navigationAiGeometryRevision() { return navigationAiRevision; }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void navigationAiBlockChanged(BlockPos pos, BlockState state, int flags,
                                          CallbackInfoReturnable<BlockState> callback) {
        if (callback.getReturnValue() != null && callback.getReturnValue() != state) navigationAiRevision++;
    }
}
