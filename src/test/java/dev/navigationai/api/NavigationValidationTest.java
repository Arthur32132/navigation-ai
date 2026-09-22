package dev.navigationai.api;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NavigationValidationTest {
    @Test
    void policyRejectsUnboundedWorkBudgets() {
        assertThrows(IllegalArgumentException.class,
                () -> new NavigationPolicy(false, false, false, false, 200_001, 128, 32, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new NavigationPolicy(false, false, false, false, 128, 16_385, 32, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new NavigationPolicy(false, false, false, false, 128, 128, 65, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new NavigationPolicy(false, false, false, false, 128, 128, 32, Double.NaN));
        assertDoesNotThrow(() -> new NavigationPolicy(false, false, false, false,
                NavigationPolicy.MAX_SEARCH_NODES, NavigationPolicy.MAX_PATH_LENGTH,
                NavigationPolicy.MAX_SNAPSHOT_RADIUS, 0.0));
    }

    @Test
    void goalsRejectNonFiniteCoordinatesAndTolerances() {
        assertThrows(IllegalArgumentException.class,
                () -> new NavigationGoal.PositionGoal(new Vec3(Double.NaN, 0.0, 0.0)));
        assertThrows(IllegalArgumentException.class,
                () -> new NavigationGoal.PositionGoal(new Vec3(0.0, 0.0, 0.0), Double.POSITIVE_INFINITY));
        assertThrows(NullPointerException.class,
                () -> new NavigationGoal.FollowGoal(null, 2.0, 4.0));
        assertDoesNotThrow(() -> new NavigationGoal.PositionGoal(new Vec3(0.0, 80.0, 0.0)));
    }
}
