package dev.navigationai.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

final class NavigationSafety {
    private NavigationSafety() { }

    static boolean dangerous(BlockState state) {
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SWEET_BERRY_BUSH) && state.getValue(SweetBerryBushBlock.AGE) > 0;
    }

    static boolean dangerous(ServerLevel level, BlockPos pos) {
        var fluid = level.getFluidState(pos).getType();
        var belowFluid = level.getFluidState(pos.below()).getType();
        return dangerous(level.getBlockState(pos)) || dangerous(level.getBlockState(pos.below()))
                || fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA
                || belowFluid == Fluids.LAVA || belowFluid == Fluids.FLOWING_LAVA;
    }

    static boolean buildingMaterial(ItemStack stack, ServerLevel level, BlockPos pos) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) return false;
        BlockState state = item.getBlock().defaultBlockState();
        return !(item.getBlock() instanceof FallingBlock) && !state.hasBlockEntity()
                && !dangerous(state) && state.isCollisionShapeFullBlock(level, pos)
                && state.canSurvive(level, pos);
    }

    static int availableBuildingBlocks(LivingEntity player, ServerLevel level) {
        int count = 0;
        for (int slot = 0; slot < ActorSupport.context(player).getInventory().getContainerSize(); slot++) {
            ItemStack stack = ActorSupport.context(player).getInventory().getItem(slot);
            if (buildingMaterial(stack, level, player.blockPosition().below())) count += stack.getCount();
        }
        return count;
    }
}
