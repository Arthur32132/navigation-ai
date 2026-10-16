package dev.navigationai.runtime;

import dev.navigationai.navigation.GridPos;
import dev.navigationai.navigation.GridTransition;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovementControllerTest {
    private GridTransition jump() {
        return new GridTransition(new GridPos(0,0,0),new GridPos(4,0,0), GridTransition.Type.JUMP,
                GridTransition.RequiredPose.STANDING,0,0,new GridTransition.Takeoff(true,.18,.25),
                new GridTransition.Landing(true,.37,.65),List.of());
    }
    private MovementController.State state(double x,double vx,boolean grounded,boolean sprint) {
        return new MovementController.State(x,0,.5,vx,0,0,grounded,sprint);
    }
    @Test void waitsForMomentumBeforeTakeoff() {
        var controller=new MovementController(); var edge=jump();
        assertFalse(controller.update(edge,state(.8,.05,true,true),false).jump());
        assertTrue(controller.update(edge,state(.8,.20,true,true),false).jump());
    }
    @Test void lostSprintBrakesBeforeWalkingOffTakeoff() {
        var input=new MovementController().update(jump(),state(.97,.03,true,false),false);
        assertFalse(input.jump()); assertFalse(input.sprint()); assertTrue(input.sneak()); assertTrue(input.forward()<=0);
    }
    @Test void offCenterStationaryStartBacksUpAndCanAccelerateAgain() {
        var controller=new MovementController();var edge=jump();
        var recover=controller.update(edge,state(.98,0,true,true),false);
        assertFalse(recover.jump());assertTrue(recover.sneak());assertTrue(recover.forward()<0);
        assertTrue(controller.update(edge,state(.75,-.05,true,true),false).forward()<0);
        var launch=controller.update(edge,state(.5,-.03,true,true),false);
        assertFalse(launch.sneak());
        for(int n=0;n<8;n++)launch=controller.update(edge,state(.5,0,true,true),false);
        assertTrue(launch.forward()>0);
        assertTrue(controller.update(edge,state(.8,.20,true,true),false).jump());
    }
    @Test void settlesLandingWithoutLaunchingTheSameJumpAgain() {
        var controller=new MovementController(); var edge=jump();
        controller.update(edge,state(2.5,.2,false,true),false);
        var input=controller.update(edge,state(4.3,.2,true,true),false);
        assertEquals(MovementController.Phase.LANDING,input.phase());
        assertFalse(input.jump()); assertFalse(input.sprint()); assertTrue(input.forward()<0);
    }
    @Test void countersSidewaysImpulseInFlight() {
        var input=new MovementController().update(jump(),
                new MovementController.State(2,1,.5,.18,0,.15,false,true),false);
        assertTrue(input.strafe()>0); assertFalse(input.jump());
    }
    @Test void acceleratesWithBoundedInputRamp() {
        var controller=new MovementController();var edge=jump();
        float first=controller.update(edge,state(.5,0,true,true),false).forward();
        float second=controller.update(edge,state(.5,0,true,true),false).forward();
        assertEquals(.35f,first,.001); assertEquals(.70f,second,.001);
    }
    @Test void preservesSprintImpulseBeforeTheLandingBrakingZone() {
        var input = new MovementController().update(jump(),
                new MovementController.State(2,1,.5,.4,.2,0,false,true),false);
        assertTrue(input.forward() > 0);
    }
}
