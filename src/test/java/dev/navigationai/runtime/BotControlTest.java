package dev.navigationai.runtime;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BotControlTest {
    @Test void everyOtherControllerIsCancelledAndRequesterIsPreserved() {
        var control = new BotControl();
        var cancelled = new ArrayList<BotControl.Kind>();
        for (var kind : BotControl.Kind.values()) control.register(kind, () -> cancelled.add(kind));
        long claim = control.claim(BotControl.Kind.GATHER);
        assertTrue(control.current(claim));
        assertEquals(List.of(BotControl.Kind.ACTIVITY, BotControl.Kind.NAVIGATION,
                BotControl.Kind.MINING, BotControl.Kind.COMBAT, BotControl.Kind.MANUAL), cancelled);
    }
    @Test void reentrantRequestWinsWithoutCancellingItsRemainingControllers() {
        var control = new BotControl();
        boolean[] entered = { false };
        long[] newer = { 0 };
        control.register(BotControl.Kind.GATHER, () -> {
            if (!entered[0]) { entered[0] = true; newer[0] = control.claim(BotControl.Kind.COMBAT); }
        });
        assertEquals(-1, control.claim(BotControl.Kind.NAVIGATION));
        assertTrue(control.current(newer[0]));
    }
}
