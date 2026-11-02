package advanced_teleport.detection;

import advanced_teleport.graph.GraphData.RoadKind;
import advanced_teleport.graph.GraphData.Road;
import advanced_teleport.graph.GraphData.Point;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
}
