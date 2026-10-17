package dev.navigationai.api;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TickWorkBudgetTest {
    @Test void acceleratedTickReservesSpaceForTheRestOfMinecraft(){
        long target=2_500_000L;
        assertTrue(TickWorkBudget.planningNanos(target,1_000_000)<target/2);
        assertTrue(TickWorkBudget.residentDecisionNanos(target)<target/2);
        assertTrue(TickWorkBudget.overloaded(target,4_000_000));
        assertFalse(TickWorkBudget.overloaded(50_000_000L,4_000_000));
    }
    @Test void pressureReducesWorkAndNormalSpeedKeepsItsPreviousAllowance(){
        assertEquals(5_000_000,TickWorkBudget.planningNanos(50_000_000,20_000_000));
        assertEquals(2_000_000,TickWorkBudget.residentDecisionNanos(50_000_000));
        assertTrue(TickWorkBudget.planningNanos(2_500_000,4_000_000)<TickWorkBudget.planningNanos(2_500_000,1_000_000));
        assertTrue(TickWorkBudget.planningNanos(100_000,100_000)>0);
    }
}
