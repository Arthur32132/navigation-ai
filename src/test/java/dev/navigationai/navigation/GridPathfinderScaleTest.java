package dev.navigationai.navigation;

import dev.navigationai.api.NavigationPolicy;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Search size on open ground where long jumps are possible in every direction. */
class GridPathfinderScaleTest {
    private static final NavigationPolicy TRAVEL=new NavigationPolicy(false,false,true,true,32_000,1024,32,200,2);
    private static GridWorld field(int radius){
        return new GridWorld(){
            public boolean passable(GridPos p){return p.y()==0&&Math.abs(p.x())<=radius&&Math.abs(p.z())<=radius;}
            public boolean isFrontier(GridPos p){return Math.abs(p.x())==radius||Math.abs(p.z())==radius;}
            public boolean canJump(GridPos from,GridPos to){return passable(from)&&passable(to);}
        };
    }
    @Test void cheaperLabelsReplaceShorterOnesFarFromTheLengthLimit(){
        var path=GridPathfinder.find(field(16),new GridPos(-14,0,-14),new GridPos(14,0,14),TRAVEL);
        assertFalse(path.nodes().isEmpty());
        // One label per cell would visit at most the 33x33 field; Pareto labels on (cost, steps) visited ~1900.
        assertTrue(path.nodesVisited()<=33*33,"visited "+path.nodesVisited());
    }
    @Test void neighbouringCellsDoNotShareHashes(){
        var hashes=new HashSet<Integer>();
        for(int x=-32;x<32;x++)for(int y=-20;y<44;y++)for(int z=-32;z<32;z++)hashes.add(new GridPos(x,y,z).hashCode());
        // The record default (961x+31y+z) gives about 77k distinct values for these 262k cells.
        assertTrue(hashes.size()>260_000,"distinct "+hashes.size());
    }
    @Test void stepLimitStillPrefersALongerButFeasibleRoute(){
        GridPos s=new GridPos(0,0,0),a=new GridPos(1,0,0),b=new GridPos(0,0,1),m=new GridPos(1,0,1),g=new GridPos(2,0,1);
        Set<GridPos> cells=Set.of(s,a,b,m,g);
        GridWorld world=new GridWorld(){
            public boolean passable(GridPos p){return cells.contains(p);}
            public double cost(GridPos from,GridPos to){return GridWorld.super.cost(from,to)+(to.equals(a)?5:0);}
        };
        assertEquals(g,GridPathfinder.find(world,s,g,new NavigationPolicy(false,false,false,false,512,3,8,0,2)).nodes().getLast());
    }
}
