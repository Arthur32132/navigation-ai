package dev.navigationai.api;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class InfrastructureWaitAccountingTest {
    private static final class Route implements NavigationTask{
        long ticks;public long infrastructureWaitTicks(){return ticks;}
        public NavigationStatus status(){return NavigationStatus.COMPUTING;}
        public CompletableFuture<NavigationResult> completion(){return new CompletableFuture<>();}
        public void cancel(){}
    }
    @Test void routeReplacementAndRepeatedObservationDoNotLoseOrDuplicateWait(){
        var time=new InfrastructureWaitAccounting();var a=new Route();var b=new Route();
        a.ticks=5;assertEquals(5,time.observe(a,null));assertEquals(5,time.observe(a,null));
        a.ticks=8;assertEquals(8,time.observe(a,null));
        a.ticks=10;b.ticks=2;assertEquals(12,time.observe(b,null));
        b.ticks=4;assertEquals(14,time.observe(null,null));
        time.reset();assertEquals(0,time.observe(null,null));
    }
}
