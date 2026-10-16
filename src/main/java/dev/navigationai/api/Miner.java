package dev.navigationai.api;

import net.minecraft.core.BlockPos;

public interface Miner {
    MiningTask breakBlock(BlockPos position);
    MiningStatus status();
    MiningTask task();

    /** Excavate by walking and using normal mining, with safety checks before every block. */
    default ExcavationTask excavate(ExcavationRequest request, ExcavationPolicy policy) {
        throw new UnsupportedOperationException("Excavation is not supported by this miner");
    }
    default ExcavationTask excavate(ExcavationRequest request) {
        return excavate(request, ExcavationPolicy.defaults());
    }
    default ExcavationStatus excavationStatus() { return ExcavationStatus.IDLE; }
    default ExcavationTask excavationTask() { return null; }
}
