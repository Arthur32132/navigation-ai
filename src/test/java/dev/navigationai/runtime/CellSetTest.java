package dev.navigationai.runtime;

import dev.navigationai.navigation.GridPos;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CellSetTest {
    private static final CellSet.CellBox BOX=CellSet.CellBox.of(-3,3,60,70,-2,2);
    @Test void boxIndexRoundTripsEveryCell(){
        var seen=new HashSet<Integer>();
        for(int x=-3;x<=3;x++)for(int y=60;y<=70;y++)for(int z=-2;z<=2;z++){
            int i=BOX.index(x,y,z);assertTrue(i>=0&&seen.add(i));assertEquals(new GridPos(x,y,z),BOX.cell(i));
        }
        assertEquals(BOX.volume(),seen.size());
        assertEquals(-1,BOX.index(4,65,0));assertEquals(-1,BOX.index(0,59,0));assertEquals(-1,BOX.index(0,65,3));
    }
    @Test void behavesLikeAHashSetInsideAndOutsideTheBox(){
        var cells=new CellSet(BOX);var reference=new HashSet<GridPos>();
        var inside=new GridPos(1,65,-1);var outside=new GridPos(100,65,0);
        for(var p:Set.of(inside,outside,new GridPos(-3,60,-2),new GridPos(3,70,2))){assertTrue(cells.add(p));reference.add(p);}
        assertFalse(cells.add(inside));
        assertEquals(reference,cells);assertEquals(reference,new HashSet<>(cells));
        assertTrue(cells.contains(outside));assertTrue(cells.remove(inside));assertFalse(cells.contains(inside));
        assertEquals(3,cells.size());
    }
    @Test void mapKeepsValuesAndMergesLikeAHashMap(){
        var costs=new CellMap<Double>(BOX);
        costs.put(new GridPos(0,65,0),1.5);costs.merge(new GridPos(0,65,0),3.0,Math::max);costs.put(new GridPos(50,0,50),2.0);
        assertEquals(3.0,costs.get(new GridPos(0,65,0)));assertEquals(2.0,costs.getOrDefault(new GridPos(50,0,50),0.0));
        assertEquals(0.0,costs.getOrDefault(new GridPos(1,65,0),0.0));assertEquals(2,costs.size());assertEquals(2,costs.entrySet().size());
    }
}
