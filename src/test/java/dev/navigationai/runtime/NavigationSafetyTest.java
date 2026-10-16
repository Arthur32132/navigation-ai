package dev.navigationai.runtime;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NavigationSafetyTest {
    @BeforeAll static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void matureBerryBushesAreHazardsWhileNewlyPlantedBushesAreSafe() {
        var bush = Blocks.SWEET_BERRY_BUSH.defaultBlockState();
        assertFalse(NavigationSafety.dangerous(bush.setValue(SweetBerryBushBlock.AGE, 0)));
        for (int age = 1; age <= 3; age++) {
            assertTrue(NavigationSafety.dangerous(bush.setValue(SweetBerryBushBlock.AGE, age)), "berry age " + age);
        }
    }

    @Test void existingBlockHazardsRemainDangerous() {
        for (Block hazard : new Block[] {Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.CAMPFIRE,
                Blocks.SOUL_CAMPFIRE, Blocks.MAGMA_BLOCK, Blocks.CACTUS, Blocks.POWDER_SNOW}) {
            assertTrue(NavigationSafety.dangerous(hazard.defaultBlockState()), hazard.toString());
        }
    }

    @Test void ordinaryDryTerrainRemainsSafe() {
        for (Block terrain : new Block[] {Blocks.AIR, Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.STONE, Blocks.OAK_PLANKS}) {
            assertFalse(NavigationSafety.dangerous(terrain.defaultBlockState()), terrain.toString());
        }
    }
}
