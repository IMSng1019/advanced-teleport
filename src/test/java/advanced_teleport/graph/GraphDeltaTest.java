package advanced_teleport.graph;

import advanced_teleport.graph.GraphData.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GraphDeltaTest {
    private Road road(String id, double end) {
        return new Road(id,"minecraft:overworld",List.of(new Point("minecraft:overworld",0,64,0),
                new Point("minecraft:overworld",end,64,0)),RoadKind.API,true);
    }
    private Snapshot graph(long revision,Road... roads) {return new Snapshot(revision,List.of(),List.of(roads),List.of(),List.of());}
    @Test void deltaCarriesOnlyChangesAndReconstructsTarget() {
        var keep=road("keep",2);var old=road("change",5);
        var before=graph(10,keep,old,road("remove",9));
        var after=graph(11,keep,road("change",6),road("new",20));
        var delta=GraphDelta.between(before,after);
        assertEquals(2,delta.roads().upserts().size());
        assertEquals(List.of("remove"),delta.roads().removed());
        assertTrue(delta.roads().order().isEmpty());
        assertEquals(after,delta.apply(before));
    }
    @Test void preservesExplicitReorderingAndRejectsWrongBase() {
        var a=road("a",1);var b=road("b",2);
        var before=graph(1,a,b);var after=graph(2,b,a);
        var delta=GraphDelta.between(before,after);
        assertEquals(after,delta.apply(before));
        assertThrows(IllegalArgumentException.class,()->delta.apply(graph(0,a,b)));
    }
}
