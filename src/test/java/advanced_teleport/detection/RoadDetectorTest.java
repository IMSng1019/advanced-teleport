package advanced_teleport.detection;

import advanced_teleport.graph.GraphData.RoadKind;
import advanced_teleport.graph.GraphData.Road;
import advanced_teleport.graph.GraphData.Point;
import advanced_teleport.graph.GraphData.Snapshot;
import advanced_teleport.graph.GraphEngine;
import advanced_teleport.graph.GraphSettings;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import static advanced_teleport.detection.RoadDetector.*;
import static org.junit.jupiter.api.Assertions.*;

class RoadDetectorTest {
    private static final String DIM = "minecraft:overworld";
    private static final DetectorSettings SETTINGS = DetectorSettings.defaults();

    private static final class Grid implements BlockSource {
        final Map<Cell, BlockSample> blocks = new HashMap<>();
        public BlockSample blockAt(int x, int y, int z) { return blocks.getOrDefault(new Cell(x,y,z), BlockSample.AIR); }
        void put(int x, int y, int z, BlockSample sample) { blocks.put(new Cell(x,y,z), sample); }
        void rail(int x,int z,Shape shape,boolean powered) { put(x,64,z,new BlockSample(Kind.POWERED_RAIL,shape,powered)); }
    }

    @Test void activeParallelRailsProduceLocalLineSegments() {
        Grid grid = parallelRails();
        var result = scan(grid, DIM, new Cell(0,64,0), SETTINGS);
        assertTrue(result.roads().stream().anyMatch(r -> r.kind() == RoadKind.RAIL));
        assertTrue(result.roads().stream().allMatch(r -> !r.protectedEntry()));
    }

    @Test void isolatedOrUnpoweredRailsAreNotRoads() {
        Grid grid = new Grid();
        for (int x=-8;x<=8;x++) grid.rail(x,0,Shape.EAST_WEST,true);
        assertTrue(scan(grid,DIM,new Cell(0,64,0),SETTINGS).roads().isEmpty());
        for (int x=-8;x<=8;x++) grid.rail(x,2,Shape.EAST_WEST,false);
        assertTrue(scan(grid,DIM,new Cell(0,64,0),SETTINGS).roads().isEmpty());
    }

    @Test void perpendicularRailBesideLaneDoesNotCountAsParallel() {
        Grid grid = parallelRails();
        for (int x=-8;x<=8;x++) grid.rail(x,2,Shape.NORTH_SOUTH,true);
        assertTrue(scan(grid,DIM,new Cell(0,64,0),SETTINGS).roads().isEmpty());
    }

    @Test void guardedThreeWideIceRoadProducesCenterline() {
        Grid grid = iceStraight(3);
        var result = scan(grid,DIM,new Cell(0,64,1),SETTINGS);
        assertTrue(result.roads().stream().anyMatch(r -> r.kind()==RoadKind.ICE));
        assertTrue(result.roads().stream().allMatch(r -> r.points().stream().allMatch(p -> p.z()==1.5)));
    }

    @Test void overwideIceAndMissingGuardrailsAreRejected() {
        assertTrue(scan(iceStraight(6),DIM,new Cell(0,64,1),SETTINGS).roads().isEmpty());
        Grid grid=iceStraight(3);
        for (int x=-12;x<=12;x++) grid.put(x,65,3,BlockSample.AIR);
        assertTrue(scan(grid,DIM,new Cell(0,64,1),SETTINGS).roads().isEmpty());
    }

    @Test void deletionReexaminesOldSegmentIds() {
        Grid grid=parallelRails();
        var previous=scan(grid,DIM,new Cell(0,64,0),SETTINGS);
        grid.put(0,64,0,BlockSample.AIR);
        var next=scan(grid,DIM,new Cell(0,64,0),SETTINGS);
        var removed=previous.roads().stream().filter(r->next.roads().stream().noneMatch(n->n.id().equals(r.id()))).toList();
        assertFalse(removed.isEmpty());
        assertTrue(removed.stream().allMatch(r->next.examinedIds().contains(r.id())));
    }

    @Test void unloadedNeighborsNeverAuthorizeDeletion() {
        Grid grid=parallelRails();
        var previous=scan(grid,DIM,new Cell(0,64,0),SETTINGS);
        BlockSource unloaded=(x,y,z)->z>=2?BlockSample.UNLOADED:grid.blockAt(x,y,z);
        var next=scan(unloaded,DIM,new Cell(0,64,0),SETTINGS);
        assertFalse(next.complete());
        assertTrue(previous.roads().stream().filter(r->r.points().get(0).z()==0.5)
                .noneMatch(r->next.examinedIds().contains(r.id())));
    }

    @Test void aLoadedParallelPartnerIsEnoughEvenWhenTheOtherSideIsUnloaded() {
        Grid grid=parallelRails();
        BlockSource source=(x,y,z)->z<0?BlockSample.UNLOADED:grid.blockAt(x,y,z);
        var result=scan(source,DIM,new Cell(0,64,0),SETTINGS);
        assertTrue(result.roads().stream().anyMatch(r->r.kind()==RoadKind.RAIL
                && r.points().get(0).z()==0.5));
    }

    @Test void parallelAscendingRailsConnectAcrossTheirHeightChange() {
        Grid grid=new Grid();
        for(int lane:new int[]{0,2}) {
            grid.put(0,64,lane,new BlockSample(Kind.POWERED_RAIL,Shape.ASCENDING_EAST,true));
            grid.put(1,65,lane,new BlockSample(Kind.POWERED_RAIL,Shape.EAST_WEST,true));
        }
        var result=scan(grid,DIM,new Cell(0,64,0),SETTINGS);
        assertTrue(result.roads().stream().anyMatch(r->r.kind()==RoadKind.RAIL
                && r.points().get(0).y()!=r.points().get(1).y()));
    }

    @Test void removingCornerRechecksItsValidSlopedTransition() {
        Grid grid=pairedBend(2);
        for(int lane:new int[]{0,2})for(int x=3;x<=12;x++) {
            grid.put(x,64,lane,BlockSample.AIR);
            grid.put(x,63,lane,new BlockSample(Kind.POWERED_RAIL,x==3?Shape.ASCENDING_WEST:Shape.EAST_WEST,true));
        }
        var before=scan(grid,DIM,new Cell(3,63,0),SETTINGS);
        var farSegment=before.roads().stream().filter(r->r.points().get(0).x()==2.5
                &&r.points().get(0).z()==.5&&r.points().get(0).y()!=r.points().get(1).y()).findFirst();
        assertTrue(farSegment.isPresent());
        grid.put(0,64,0,BlockSample.AIR);
        var after=scan(grid,DIM,new Cell(0,64,0),SETTINGS);
        assertTrue(after.examinedIds().contains(farSegment.orElseThrow().id()));
        assertTrue(after.roads().stream().noneMatch(r->r.id().equals(farSegment.orElseThrow().id())));
    }

    @Test void largerConfiguredCornerGapsExpandTheInvalidationRadius() {
        Grid grid=pairedBend(3);
        DetectorSettings settings=new DetectorSettings(2,4,5,3,5);
        var before=scan(grid,DIM,new Cell(3,64,0),settings);
        var farSegment=before.roads().stream().filter(r->r.points().get(0).x()==3.5&&r.points().get(0).z()==.5).findFirst();
        assertTrue(farSegment.isPresent());
        grid.put(3,64,3,BlockSample.AIR);
        var after=scan(grid,DIM,new Cell(3,64,3),settings);
        assertTrue(after.examinedIds().contains(farSegment.orElseThrow().id()));
        assertTrue(after.roads().stream().noneMatch(r->r.id().equals(farSegment.orElseThrow().id())));
    }

    @Test void guardedIceCornerUsesTwoPerpendicularArms() {
        Grid grid=new Grid();
        for(int x=-12;x<=2;x++) for(int z=0;z<=2;z++) grid.put(x,64,z,BlockSample.BLUE_ICE);
        for(int x=0;x<=2;x++) for(int z=0;z<=12;z++) grid.put(x,64,z,BlockSample.BLUE_ICE);
        for(int x=-12;x<=2;x++) grid.put(x,65,-1,BlockSample.SOLID);
        for(int x=-12;x<0;x++) grid.put(x,65,3,BlockSample.SOLID);
        for(int z=0;z<=12;z++) grid.put(3,65,z,BlockSample.SOLID);
        for(int z=3;z<=12;z++) grid.put(-1,65,z,BlockSample.SOLID);
        var result=scan(grid,DIM,new Cell(1,64,1),SETTINGS);
        assertTrue(result.roads().stream().anyMatch(r->r.kind()==RoadKind.ICE && r.points().size()==3));
    }

    @Test void guardedIceCrossroadsConnectEveryArmAtThreeAndFiveBlocksWide() {
        for(int width:new int[]{3,5}) assertJunctionConnected(iceJunction(width,15),width,15);
    }

    @Test void guardedIceTJunctionsConnectEveryArmInEveryOrientation() {
        for(int width:new int[]{3,5}) for(int closed=0;closed<4;closed++) {
            int arms=15^(1<<closed);
            assertJunctionConnected(iceJunction(width,arms),width,arms);
        }
    }

    @Test void guardedIceLCornersRemainConnectedInEveryOrientation() {
        for(int width:new int[]{3,5}) for(int direction=0;direction<4;direction++) {
            int arms=(1<<direction)|(1<<((direction+1)%4));
            assertJunctionConnected(iceJunction(width,arms),width,arms);
        }
    }

    @Test void iceJunctionsRejectMissingWallsBrokenFloorsAndBlockedHeadroom() {
        for(int width:new int[]{3,5}) {
            Grid missingWall=iceJunction(width,14); // North is closed; its whole side needs a wall.
            missingWall.put(width/2,65,-1,BlockSample.AIR);
            assertJunctionDisconnected(missingWall,width,"A hole in the closed side must not become another road arm");

            Grid missingArmWall=iceJunction(width,15);
            missingArmWall.put(-1,65,-1,BlockSample.AIR);
            assertJunctionDisconnected(missingArmWall,width,"An open arm must still have both guardrails");

            Grid brokenFloor=iceJunction(width,15);
            brokenFloor.put(width/2,64,width/2,BlockSample.AIR);
            assertJunctionDisconnected(brokenFloor,width,"A junction needs its complete blue-ice floor");

            Grid blockedHeadroom=iceJunction(width,15);
            blockedHeadroom.put(width/2,66,width/2,BlockSample.SOLID);
            assertJunctionDisconnected(blockedHeadroom,width,"A junction needs two clear blocks above its floor");
        }
    }

    @Test void junctionRemovalReexaminesEveryConnectionAndRejectsStaleUploads() {
        for(int width:new int[]{3,5}) {
            Grid grid=iceJunction(width,15);
            var before=scan(grid,DIM,new Cell(width/2,64,width/2),SETTINGS);
            List<Road> connections=before.roads().stream().filter(r->r.points().size()==3).toList();
            assertFalse(connections.isEmpty(),"The crossroads must first contain connecting segments");
            assertEquals(Set.copyOf(connections),Set.copyOf(validateCandidates(grid,DIM,connections,SETTINGS)),
                    "Server validation must accept the same geometry as the client scan");

            // A far arm guardrail is at the edge of the junction's dependency footprint.
            Cell removed=new Cell(width,65,width);
            grid.put(removed.x(),removed.y(),removed.z(),BlockSample.AIR);
            var after=scan(grid,DIM,removed,SETTINGS);
            Set<String> ids=connections.stream().map(Road::id).collect(Collectors.toSet());
            assertTrue(after.examinedIds().containsAll(ids),"The edit scan must revisit every old junction connection");
            assertTrue(after.roads().stream().noneMatch(r->ids.contains(r.id())));
            var checked=validateExistingCandidates(grid,DIM,connections,SETTINGS);
            assertEquals(ids,checked.rejectedIds());
            assertTrue(validateCandidates(grid,DIM,connections,SETTINGS).isEmpty());
        }
    }

    @Test void unloadedJunctionArmDoesNotAuthorizeDeletingItsExistingConnections() {
        Grid grid=iceJunction(5,15);
        List<Road> connections=scan(grid,DIM,new Cell(2,64,2),SETTINGS).roads().stream()
                .filter(r->r.points().size()==3).toList();
        assertFalse(connections.isEmpty());
        BlockSource unloaded=(x,y,z)->x>=5?BlockSample.UNLOADED:grid.blockAt(x,y,z);
        var after=scan(unloaded,DIM,new Cell(2,64,2),SETTINGS);
        assertFalse(after.complete());
        assertTrue(connections.stream().noneMatch(r->after.examinedIds().contains(r.id())));
        var checked=validateExistingCandidates(unloaded,DIM,connections,SETTINGS);
        assertEquals(connections.stream().map(Road::id).collect(Collectors.toSet()),checked.unknownIds());
        assertTrue(checked.rejectedIds().isEmpty());
    }

    @Test void ordinaryRailCornersAndTheirApproachesStayConnected() {
        Grid grid=pairedBend(2);
        var result=scan(grid,DIM,new Cell(0,64,0),SETTINGS);
        assertTrue(result.roads().stream().anyMatch(r->r.points().stream().anyMatch(p->p.x()==0.5&&p.z()==0.5)));
        assertTrue(result.roads().stream().anyMatch(r->r.points().stream().anyMatch(p->p.x()==1.5&&p.z()==0.5)));
    }

    @Test void isolatedCornerPairDoesNotQualifySingleOrdinaryApproach() {
        Grid grid=new Grid();
        grid.put(0,64,0,new BlockSample(Kind.RAIL,Shape.SOUTH_EAST,false));
        grid.put(2,64,2,new BlockSample(Kind.RAIL,Shape.SOUTH_EAST,false));
        for(int x=1;x<=4;x++)grid.put(x,64,0,new BlockSample(Kind.RAIL,Shape.EAST_WEST,false));
        assertTrue(scan(grid,DIM,new Cell(0,64,0),SETTINGS).roads().isEmpty(),
                "A corner pair is not proof of two continuous powered lanes");
    }

    @Test void pairedBendStillWorksBesideUnloadedUnrelatedChunks() {
        Grid grid=pairedBend(2);
        BlockSource source=(x,y,z)->x<0||z<0?BlockSample.UNLOADED:grid.blockAt(x,y,z);
        var result=scan(source,DIM,new Cell(0,64,0),SETTINGS);
        assertEquals(2,result.roads().stream().filter(r->r.points().stream()
                .anyMatch(p->p.x()==.5&&p.z()==.5)).count());
    }

    @Test void cornerRequiresBothLanesOnBothArms() {
        Grid grid=pairedBend(2);
        for(int x=3;x<=12;x++)grid.put(x,64,2,BlockSample.AIR);
        var result=scan(grid,DIM,new Cell(0,64,0),SETTINGS);
        assertTrue(result.roads().stream().noneMatch(r->r.points().stream()
                .anyMatch(p->p.x()==.5&&p.z()==.5)),"Missing second east arm must invalidate the bend");
    }

    @Test void serverValidatesExactUploadedGeometryWithoutScanningUnrelatedArea() {
        Grid grid=parallelRails();
        Road road=scan(grid,DIM,new Cell(0,64,0),SETTINGS).roads().stream()
                .filter(r->r.points().get(0).x()==.5&&r.points().get(0).z()==.5).findFirst().orElseThrow();
        int[] reads={0};
        BlockSource counted=(x,y,z)->{reads[0]++;return grid.blockAt(x,y,z);};
        assertEquals(List.of(road),validateCandidates(counted,DIM,List.of(road),SETTINGS));
        assertTrue(reads[0]<100,"One uploaded straight segment must not trigger an area scan");
        Road forged=new Road(road.id(),DIM,List.of(new Point(DIM,500,64,0),new Point(DIM,501,64,0)),RoadKind.RAIL,false);
        assertTrue(validateCandidates(grid,DIM,List.of(forged),SETTINGS).isEmpty());
        assertTrue(validateCandidates(grid,DIM,List.of(new Road(road.id(),DIM,road.points(),RoadKind.RAIL,true)),SETTINGS).isEmpty());
        assertTrue(validateCandidates(grid,"minecraft:the_nether",List.of(road),SETTINGS).isEmpty());
        assertTrue(validateCandidates((x,y,z)->BlockSample.UNLOADED,DIM,List.of(road),SETTINGS).isEmpty());
        assertTrue(validateCandidates(grid,DIM,List.of(new Road("auto:rail:garbage",DIM,road.points(),RoadKind.RAIL,false)),SETTINGS).isEmpty());
    }

    @Test void trustedExistingCandidatesReturnUpdatedGeometryAndSeparateUnknownFromRemoved() {
        Grid grid=iceStraight(3);
        Road old=scan(grid,DIM,new Cell(0,64,0),SETTINGS).roads().stream()
                .filter(r->r.id().equals("auto:ice:"+DIM+":0:64:0:x")).findFirst().orElseThrow();
        Grid wider=iceStraight(5);
        var changed=validateExistingCandidates(wider,DIM,List.of(old),SETTINGS);
        assertEquals(1,changed.accepted().size());
        assertEquals(2.5,changed.accepted().get(0).points().get(0).z());
        assertTrue(validateCandidates(wider,DIM,List.of(old),SETTINGS).isEmpty(),"Upload cannot claim stale geometry");
        var missing=validateExistingCandidates(new Grid(),DIM,List.of(old),SETTINGS);
        assertEquals(java.util.Set.of(old.id()),missing.rejectedIds());
        var unloaded=validateExistingCandidates((x,y,z)->BlockSample.UNLOADED,DIM,List.of(old),SETTINGS);
        assertEquals(java.util.Set.of(old.id()),unloaded.unknownIds());
        assertTrue(unloaded.rejectedIds().isEmpty());
        assertEquals(new Cell(0,64,0),candidateAnchor(DIM,old.id()).orElseThrow());
        assertTrue(candidateAnchor(DIM,old.id().replace(":0:64:0:",":00:64:0:")).isEmpty());
        assertTrue(candidateAnchor("minecraft:the_nether",old.id()).isEmpty());
    }

    private static Grid pairedBend(int separation) {
        Grid grid=new Grid();
        for(int x=1;x<=12;x++)grid.rail(x,0,Shape.EAST_WEST,true);
        for(int z=1;z<=12;z++)grid.rail(0,z,Shape.NORTH_SOUTH,true);
        for(int x=separation+1;x<=12;x++)grid.rail(x,separation,Shape.EAST_WEST,true);
        for(int z=separation+1;z<=12;z++)grid.rail(separation,z,Shape.NORTH_SOUTH,true);
        grid.put(0,64,0,new BlockSample(Kind.RAIL,Shape.SOUTH_EAST,false));
        grid.put(separation,64,separation,new BlockSample(Kind.RAIL,Shape.SOUTH_EAST,false));
        return grid;
    }

    private static Grid parallelRails() {
        Grid grid=new Grid();
        for(int x=-12;x<=12;x++) {grid.rail(x,0,Shape.EAST_WEST,true);grid.rail(x,2,Shape.EAST_WEST,true);}
        return grid;
    }

    private static Grid iceStraight(int width) {
        Grid grid=new Grid();
        for(int x=-12;x<=12;x++) {
            for(int z=0;z<width;z++) grid.put(x,64,z,BlockSample.BLUE_ICE);
            grid.put(x,65,-1,BlockSample.SOLID);grid.put(x,65,width,BlockSample.SOLID);
        }
        return grid;
    }

    /** Bit order is north, east, south, west; walls follow the exterior of the entire ice floor. */
    private static Grid iceJunction(int width,int arms) {
        Grid grid=new Grid();
        for(int x=-12;x<width+12;x++) for(int z=-12;z<width+12;z++) {
            boolean withinX=x>=0&&x<width,withinZ=z>=0&&z<width;
            if(withinX&&withinZ || withinX&&(z<0&&(arms&1)!=0 || z>=width&&(arms&4)!=0)
                    || withinZ&&(x<0&&(arms&8)!=0 || x>=width&&(arms&2)!=0))
                grid.put(x,64,z,BlockSample.BLUE_ICE);
        }
        for(Cell floor:List.copyOf(grid.blocks.keySet())) for(int[] direction:new int[][]{{0,-1},{1,0},{0,1},{-1,0}}) {
            Cell beside=floor.add(direction[0],0,direction[1]);
            if(grid.blockAt(beside.x(),beside.y(),beside.z()).kind()!=Kind.BLUE_ICE)
                grid.put(beside.x(),65,beside.z(),BlockSample.SOLID);
        }
        return grid;
    }

    private static Point armPoint(int width,int direction) {
        return switch(direction) {
            case 0->new Point(DIM,width/2.0,65,-3.5);
            case 1->new Point(DIM,width+3.5,65,width/2.0);
            case 2->new Point(DIM,width/2.0,65,width+3.5);
            case 3->new Point(DIM,-3.5,65,width/2.0);
            default->throw new IllegalArgumentException("Invalid fixture arm");
        };
    }

    private static GraphEngine junctionGraph(Grid grid,int width) {
        var roads=scan(grid,DIM,new Cell(width/2,64,width/2),SETTINGS).roads();
        return new GraphEngine(new Snapshot(1,List.of(),roads,List.of(),List.of()),new GraphSettings(2,3,2,3));
    }

    private static void assertJunctionConnected(Grid grid,int width,int arms) {
        GraphEngine graph=junctionGraph(grid,width);
        for(int from=0;from<4;from++) for(int to=0;to<4;to++) if((arms&(1<<from))!=0&&(arms&(1<<to))!=0) {
            Point target=armPoint(width,to);
            assertEquals(target,graph.resolve(armPoint(width,from),target,false).orElseThrow(),
                    "width="+width+", arms="+arms+", from="+from+", to="+to);
        }
    }

    private static void assertJunctionDisconnected(Grid grid,int width,String message) {
        Point target=armPoint(width,1);
        assertNotEquals(target,junctionGraph(grid,width).resolve(armPoint(width,3),target,false).orElseThrow(),message);
    }
}
