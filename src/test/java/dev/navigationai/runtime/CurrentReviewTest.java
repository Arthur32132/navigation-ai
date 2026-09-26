package dev.navigationai.runtime;

import dev.navigationai.api.NavigationPolicy;
import dev.navigationai.navigation.*;
import java.util.*;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CurrentReviewTest {
    private final NavigationPolicy policy = new NavigationPolicy(false,false,false,false,10000,512,8,100,3);

    @Test void ordinaryDiagonalStepShouldBeGenerated() {
        GridWorld world = p -> p.y()==0 && p.x()>=0 && p.x()<=1 && p.z()>=0 && p.z()<=1;
        GridPos s=new GridPos(0,0,0), g=new GridPos(1,0,1);
        assertNotNull(TransitionRules.describe(world,s,g,policy));
        assertEquals(List.of(s,g),GridPathfinder.find(world,s,g,policy).nodes());
    }

    @Test void frontierMustAllowTemporaryRetreatFromDistantGoal() {
        // The only exit from the local corridor is west; the far goal is east.
        GridWorld world = p -> p.y()==0 && p.z()==0 && p.x()>=-8 && p.x()<=0;
        GridPath result=GridPathfinder.find(world,new GridPos(0,0,0),new GridPos(100,0,0),
                policy,NodeBudget.unlimited(),true);
        assertFalse(result.nodes().isEmpty(),"A reachable frontier exists at x=-8");
    }

    @Test void exploredFrontiersAreNotSelectedAgainButRemainTraversable() {
        GridWorld world = p -> p.y()==0 && p.z()==0 && p.x()>=-8 && p.x()<=0;
        Set<GridPos> explored = new HashSet<>();
        for(int x=-8;x<=-4;x++) explored.add(new GridPos(x,0,0));
        GridPath result=GridPathfinder.findToAny(world,new GridPos(0,0,0),Set.of(new GridPos(100,0,0)),
                new GridPos(100,0,0),policy,NodeBudget.unlimited(),true,explored);
        assertEquals(new GridPos(-3,0,0),result.nodes().getLast());
        assertTrue(GridPathfinder.find(world,new GridPos(0,0,0),new GridPos(-8,0,0),policy).goalReached());
    }

    @Test void dropMustNotUseCrouchOnlyIntermediateClearanceWithStandingPose() {
        GridPos start=new GridPos(0,3,0), end=new GridPos(1,0,0);
        Set<GridPos> known=new HashSet<>();
        for(int x=-2;x<=3;x++) for(int y=-2;y<=7;y++) for(int z=-2;z<=2;z++) known.add(new GridPos(x,y,z));
        // The entry column has only 1.5 blocks of headroom at the lip.
        WorldSnapshot w=new WorldSnapshot(Set.of(start,end),Set.of(),Set.of(new GridPos(1,3,0)),Set.of(),Set.of(),
                Set.of(new GridPos(1,1,0),new GridPos(1,2,0),new GridPos(1,3,0)),Map.of(),Map.of(),
                Map.of(new GridPos(1,4,0),List.of(new AABB(1,4.5,0,2,5,1))),known,.6,1.8,Set.of(),0,
                -2,3,-2,7,-2,2,false);
        GridTransition t=TransitionRules.describe(w,start,end,policy);
        assertTrue(t==null || t.pose()==GridTransition.RequiredPose.CROUCHING,
                "Standing drop intersects the low roof over its entry column: "+t);
    }

    @Test void virtualPlacementCellsLetAStarPlanMultipleSupports() {
        Set<GridPos> passable=new HashSet<>(), placements=new HashSet<>(), known=new HashSet<>();
        for(int x=-2;x<=10;x++) for(int y=-2;y<=4;y++) for(int z=-2;z<=2;z++) {
            GridPos p=new GridPos(x,y,z); known.add(p);
            if(y==0&&z==0&&x>=0&&x<=8) {
                passable.add(p);
                if(x>0&&x<8) placements.add(p);
            }
        }
        WorldSnapshot w=new WorldSnapshot(passable,Set.of(),Set.of(),Set.of(),Set.of(),Set.of(),
                Map.of(),Map.of(),Map.of(),known,.6,1.8,placements,16,
                -2,10,-2,4,-2,2,false);
        GridPath path=GridPathfinder.find(w,new GridPos(0,0,0),new GridPos(8,0,0),
                new NavigationPolicy(false,true,false,false,10000,64,8,100,3));
        assertTrue(path.goalReached());
        assertTrue(path.transitions().stream().filter(t -> t.actions().contains(GridTransition.Action.PLACE_SUPPORT)).count()>=4);
    }
}
