package dev.navigationai.runtime;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class NavigationClearanceTest {
    @Test void villagersRetainPositiveClearanceInsideVanillaTwoBlockDoorways(){
        double height=1.9500000476837158;
        double margin=NavigationGeometry.clearanceMargin(height,false);
        assertTrue(margin>0);assertTrue(height+margin<2,"A real villager must fit its generated house doorway");
    }
    @Test void playerAndCrouchingPlayerKeepTheirExistingMargins(){
        assertEquals(.10,NavigationGeometry.clearanceMargin(1.8,false),1e-12);
        assertEquals(.02,NavigationGeometry.clearanceMargin(1.5,true),1e-12);
    }
}
