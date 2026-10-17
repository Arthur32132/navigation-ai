package dev.navigationai.runtime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class InfrastructureWaitTrackerTest {
    @Test void countsGameTicksWithoutCountingMovementOrDoubleSampling(){
        var wait=new InfrastructureWaitTracker();
        wait.sample(10,0,true);wait.sample(110,250_000_000L,false);
        assertEquals(100,wait.ticks());
        wait.sample(110,250_000_000L,false);wait.sample(300,1_000_000_000L,false);
        assertEquals(100,wait.ticks());
    }
    @Test void realWatchdogDoesNotExpireBecauseSimulationRunsFaster(){
        var wait=new InfrastructureWaitTracker();wait.sample(0,0,true);
        wait.sample(12000,29_000_000_000L,true);assertFalse(wait.expired());
        wait.sample(12001,30_000_000_000L,true);assertTrue(wait.expired());
    }
    @Test void finishingAWaitResetsItsRealWatchdog(){
        var wait=new InfrastructureWaitTracker();wait.sample(0,0,true);
        wait.sample(10,29_000_000_000L,false);wait.sample(11,40_000_000_000L,true);
        wait.sample(12,41_000_000_000L,true);assertFalse(wait.expired());
    }
}
