package dev.navigationai.runtime;

import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Shared interaction rules for planning and execution, including copper variants. */
final class PassageBlocks {
    private PassageBlocks() { }

    static boolean handOpenableTrapdoor(BlockState state) {
        return state.getBlock() instanceof TrapDoorBlock trapdoor && trapdoor.getType().canOpenByHand();
    }
}
