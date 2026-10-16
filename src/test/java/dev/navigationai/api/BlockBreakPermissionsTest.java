package dev.navigationai.api;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class BlockBreakPermissionsTest {
    @Test void independentGuardsVetoWithoutLeakingAfterUnregistration() throws Exception {
        UUID worker=UUID.randomUUID(),other=UUID.randomUUID();BlockPos house=new BlockPos(1,64,1);
        try(var ownership=BlockBreakPermissions.register((actor,pos)->!actor.equals(worker)||!pos.equals(house))){
            assertFalse(BlockBreakPermissions.isAllowed(worker,house));assertTrue(BlockBreakPermissions.isAllowed(other,house));
            try(var region=BlockBreakPermissions.register((actor,pos)->pos.getY()>=0)){
                assertFalse(BlockBreakPermissions.isAllowed(worker,BlockPos.ZERO.below()));assertFalse(BlockBreakPermissions.isAllowed(worker,house));
            }
            assertTrue(BlockBreakPermissions.isAllowed(worker,BlockPos.ZERO.below()));
        }
        assertTrue(BlockBreakPermissions.isAllowed(worker,house));
    }
}
