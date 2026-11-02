package advanced_teleport.runtime;

import advanced_teleport.graph.*;
import advanced_teleport.graph.GraphData.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GraphInfluenceTest {
    private static Point p(double x,double z){return new Point("minecraft:overworld",x,64,z);}
    @Test void unrelatedConstructionDoesNotAffectSourceNetwork() {
        Road local=new Road("local","minecraft:overworld",List.of(p(0,0),p(20,0)),RoadKind.API,true);
        Road far=new Road("far","minecraft:overworld",List.of(p(1000,0),p(1020,0)),RoadKind.API,true);
        GraphEngine graph=new GraphEngine(new Snapshot(1,List.of(),List.of(local,far),List.of(),List.of()),new GraphSettings(2,3,2,3));
        var influence=new GraphInfluence(graph,p(0,0),p(1010,0),false,8);
        assertTrue(influence.contains(p(12,2)));
        assertFalse(influence.contains(p(1010,0)));
        assertFalse(influence.contains(new Point("minecraft:the_nether",12,64,2)));
        assertFalse(influence.contains(new Point("minecraft:overworld",12,200,2)));
    }
    @Test void strictAlsoChecksDestinationRegionButNotOtherRegions() {
        var local=new Road("local","minecraft:overworld",List.of(p(0,0),p(20,0)),RoadKind.API,true);
        var target=new Road("target","minecraft:overworld",List.of(p(1000,0),p(1020,0)),RoadKind.API,true);
        var station=new Station("s","local",p(0,0),true);
        var graph=new GraphEngine(new Snapshot(1,List.of(),List.of(local,target),List.of(station),List.of()),new GraphSettings(2,3,2,3));
        var influence=new GraphInfluence(graph,p(0,0),p(1010,0),true,8);
        assertTrue(influence.contains(p(1010,0)));
        assertFalse(influence.contains(p(2000,0)));
    }
    @Test void modificationsAtAnUnregisteredSourceCanCreateItsFirstRoad() {
        var graph=new GraphEngine(Snapshot.empty(),new GraphSettings(2,3,2,3));
        var influence=new GraphInfluence(graph,p(0,0),p(1000,0),false,8);
        assertTrue(influence.contains(p(1,1)));
        assertFalse(influence.contains(p(1000,0)));
    }
}
