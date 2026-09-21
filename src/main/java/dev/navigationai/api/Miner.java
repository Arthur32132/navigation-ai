package dev.navigationai.api;

import net.minecraft.core.BlockPos;

public interface Miner {
    MiningTask breakBlock(BlockPos position);
    MiningStatus status();
    MiningTask task();
}
