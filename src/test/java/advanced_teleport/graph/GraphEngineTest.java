package advanced_teleport.graph;

import advanced_teleport.graph.GraphData.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphEngineTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final GraphSettings SETTINGS = new GraphSettings(1.5, 3, 1.5, 2);

    private static Point p(double x, double z) { return new Point(OVERWORLD, x, 64, z); }
    private static Point n(double x, double z) { return new Point(NETHER, x, 64, z); }
    private static City city(String id, double x1, double z1, double x2, double z2) {
        return new City(id, OVERWORLD, List.of(p(x1,z1), p(x2,z1), p(x2,z2), p(x1,z2)), false);
    }
    private static Road road(String id, Point... points) {
        return new Road(id, points[0].dimension(), List.of(points), RoadKind.API, true);
    }
    private static GraphEngine engine(List<City> cities, List<Road> roads, List<Station> stations, List<PortalLink> portals) {
        return new GraphEngine(new Snapshot(0, cities, roads, stations, portals), SETTINGS);
    }
    private static void assertPoint(Point expected, Point actual) {
        assertEquals(expected.dimension(), actual.dimension());
        assertEquals(expected.x(), actual.x(), 1e-8);
        assertEquals(expected.y(), actual.y(), 1e-8);
        assertEquals(expected.z(), actual.z(), 1e-8);
    }

    @Test void projectsOntoReachableCityBoundary() {
        var engine = engine(List.of(city("a",0,0,10,10)), List.of(), List.of(), List.of());
        assertPoint(p(10,5), engine.resolve(p(5,5),p(30,5),false).orElseThrow());
    }

    @Test void allowsExactDestinationInsideSameCity() {
        var engine = engine(List.of(city("a",0,0,10,10)), List.of(), List.of(), List.of());
        assertPoint(p(9,9), engine.resolve(p(1,1),p(9,9),false).orElseThrow());
    }

    @Test void deniesPlayersOutsideEveryRegion() {
        var engine = engine(List.of(city("a",0,0,10,10)), List.of(), List.of(), List.of());
        assertTrue(engine.resolve(p(20,20),p(5,5),false).isEmpty());
    }

    @Test void overlappingCitiesDoNotBecomeConnected() {
        var engine = engine(List.of(city("a",0,0,10,10), city("b",8,0,30,10)), List.of(), List.of(), List.of());
        assertPoint(p(10,5), engine.resolve(p(1,5),p(29,5),false).orElseThrow());
    }

    @Test void detectsRoadCrossingCityEvenWhenBothEndpointsAreOutside() {
        var engine = engine(List.of(city("a",0,0,10,10)),
                List.of(road("r",p(-5,5),p(20,5))), List.of(), List.of());
        assertPoint(p(20,5), engine.resolve(p(5,1),p(25,5),false).orElseThrow());
    }

    @Test void nearbyRoadConnectionsAreTransitive() {
        var engine = engine(List.of(), List.of(road("a",p(0,0),p(10,0)),
                road("b",p(11,0),p(20,0)), road("c",p(21,0),p(30,0))), List.of(), List.of());
        assertPoint(p(30,0), engine.resolve(p(0,0),p(40,0),false).orElseThrow());
    }

    @Test void crossingRoadSegmentsConnectWithoutSharedVertices() {
        var engine = engine(List.of(), List.of(road("a",p(-20,0),p(20,0)),
                road("b",p(0,-20),p(0,20))), List.of(), List.of());
        assertPoint(p(0,20), engine.resolve(p(-20,0),p(0,30),false).orElseThrow());
    }

    @Test void overpassesDoNotConnectByHorizontalProjection() {
        var high = new Point(OVERWORLD,0,80,30);
        var engine = engine(List.of(), List.of(road("a",p(-20,0),p(20,0)),
                road("b",new Point(OVERWORLD,0,80,-20),new Point(OVERWORLD,0,80,20))), List.of(), List.of());
        assertPoint(p(0,0), engine.resolve(p(-20,0),high,false).orElseThrow());
    }

    @Test void pairedPortalMakesOtherDimensionReachable() {
        var engine = engine(List.of(), List.of(road("a",p(0,0),p(10,0)), road("b",n(0,0),n(10,0))),
                List.of(), List.of(new PortalLink("portal",p(11,0),n(-1,0),false)));
        assertPoint(n(10,0), engine.resolve(p(1,0),n(50,0),false).orElseThrow());
    }

    @Test void roadBesideFarEdgeOfWidePortalConnectsToItsWholeSurface() {
        var portal=new PortalLink("wide",p(.5,0),n(0,0),false,
                new Point(OVERWORLD,19.5,80,0),n(0,0));
        var graph=engine(List.of(),List.of(road("beside",p(19.5,2),p(19.5,10)),
                road("destination",n(0,0),n(10,0))),List.of(),List.of(portal));
        assertPoint(n(10,0),graph.resolve(p(19.5,2),n(10,0),false).orElseThrow());
    }

    @Test void roadThroughPortalFaceConnectsWithoutPassingNearAnEdgeOrAnchor() {
        var portal=new PortalLink("wide",p(0,0),n(0,0),false,
                new Point(OVERWORLD,20,84,0),n(0,0));
        var crossing=road("through",new Point(OVERWORLD,10,74,-10),new Point(OVERWORLD,10,74,10));
        var graph=engine(List.of(),List.of(crossing,road("destination",n(0,0),n(10,0))),
                List.of(),List.of(portal));
        assertPoint(n(10,0),graph.resolve(crossing.points().get(0),n(10,0),false).orElseThrow());
    }

    @Test void legacyPortalConstructionAndMissingExtentsRemainPointSurfaces() {
        var legacy=new PortalLink("old",p(1,2),n(3,4),false);
        var missing=new PortalLink("old",p(1,2),n(3,4),false,null,null);
        assertEquals(legacy,missing);
        assertEquals(legacy.first(),legacy.firstExtent());
        assertEquals(legacy.second(),legacy.secondExtent());
        assertThrows(IllegalArgumentException.class,()->new PortalLink("dimension",p(0,0),n(0,0),false,n(2,0),n(0,0)));
        assertThrows(IllegalArgumentException.class,()->new PortalLink("box",p(0,0),n(0,0),false,new Point(OVERWORLD,1,65,1),n(0,0)));
    }

    @Test void missingPortalPairDoesNotCompareCoordinatesAcrossDimensions() {
        var engine = engine(List.of(), List.of(road("a",p(0,0),p(10,0)), road("b",n(0,0),n(10,0))),
                List.of(), List.of());
        assertTrue(engine.resolve(p(1,0),n(5,0),false).isEmpty());
    }

    @Test void strictRequiresBeingBesideARegisteredStation() {
        var engine = engine(List.of(), List.of(road("r",p(0,0),p(100,0))),
                List.of(new Station("s","r",p(0,0),true)), List.of());
        assertTrue(engine.resolve(p(30,0),p(50,0),true).isEmpty());
    }

    @Test void strictChoosesNearestStationToTargetRegionRatherThanTargetPoint() {
        var engine = engine(List.of(city("target",0,10,100,20)), List.of(road("r",p(0,0),p(50,5))),
                List.of(new Station("a","r",p(0,0),true),new Station("b","r",p(50,5),true)), List.of());
        assertPoint(p(50,5),engine.resolve(p(0,0),p(0,10),true).orElseThrow());
    }

    @Test void strictWithinSameComponentBreaksTiesTowardRequestedPoint() {
        var engine = engine(List.of(), List.of(road("r",p(0,0),p(100,0))),
                List.of(new Station("a","r",p(0,0),true),new Station("b","r",p(100,0),true)), List.of());
        assertPoint(p(100,0),engine.resolve(p(0,0),p(90,0),true).orElseThrow());
    }

    @Test void crossDimensionStrictStationChoiceUsesPortalDistanceInsteadOfIds() {
        var roads=List.of(road("origin",p(0,0),p(100,0)),road("destination",n(0,0),n(100,0)));
        var portals=List.of(new PortalLink("portal",p(100,0),n(0,0),false));
        var first=engine(List.of(),roads,List.of(new Station("a_far","origin",p(0,0),true),
                new Station("z_near","origin",p(100,0),true)),portals);
        var renamed=engine(List.of(),roads,List.of(new Station("z_far","origin",p(0,0),true),
                new Station("a_near","origin",p(100,0),true)),portals);
        assertPoint(p(100,0),first.resolve(p(0,0),n(90,0),true).orElseThrow());
        assertPoint(p(100,0),renamed.resolve(p(0,0),n(90,0),true).orElseThrow());
    }

    @Test void crossDimensionStrictDistanceCanTraverseMoreThanOnePortalPair() {
        String end="minecraft:the_end";
        Point e0=new Point(end,0,64,0),e90=new Point(end,90,64,0);
        var roads=List.of(road("origin",p(0,0),p(100,0)),road("middle",n(0,0),n(20,0)),road("end",e0,e90));
        var portals=List.of(new PortalLink("first",p(100,0),n(0,0),false),
                new PortalLink("second",n(20,0),e0,false));
        var graph=engine(List.of(),roads,List.of(new Station("a_far","origin",p(0,0),true),
                new Station("z_near","origin",p(100,0),true)),portals);
        assertPoint(p(100,0),graph.resolve(p(0,0),e90,true).orElseThrow());
    }

    @Test void strictPortalMetricDoesNotUseAnEndpointWithoutAConnectedRoad() {
        var roads=List.of(road("origin",p(0,0),p(100,0)),road("destination",n(0,0),n(100,0)));
        var portals=List.of(new PortalLink("valid",p(100,0),n(0,0),false),
                new PortalLink("unserved",p(0,5),n(90,0),false));
        var graph=engine(List.of(),roads,List.of(new Station("far","origin",p(0,0),true),
                new Station("near","origin",p(100,0),true)),portals);
        assertPoint(p(100,0),graph.resolve(p(0,0),n(90,0),true).orElseThrow());
    }

    @Test void reachableGeometryViewsExcludeDisconnectedCitiesAndRoads() {
        var portal=new PortalLink("portal",p(10,0),n(0,0),false);
        var graph=engine(List.of(city("home",-1,-1,11,1),city("elsewhere",50,50,60,60)),
                List.of(road("origin",p(0,0),p(10,0)),road("destination",n(0,0),n(10,0)),road("separate",p(25,0),p(35,0))),
                List.of(new Station("station","origin",p(0,0),true)),List.of(portal));
        assertEquals(List.of("origin","destination"),graph.reachableRoads(p(0,0),true).stream().map(Road::id).toList());
        assertEquals(List.of("home"),graph.reachableCities(p(0,0),false).stream().map(City::id).toList());
        assertEquals(List.of(portal),graph.reachablePortals(p(0,0),true));
        assertTrue(graph.reachableRoads(p(25,50),false).isEmpty());
    }

    @Test void rectangleDistanceHandlesFacesEdgesHorizontalSurfacesAndDegeneratePoints() {
        Point low=new Point(OVERWORLD,0,0,0),high=new Point(OVERWORLD,10,10,0);
        assertEquals(0,Geometry.segmentRectangleDistanceSquared(new Point(OVERWORLD,5,5,-2),new Point(OVERWORLD,5,5,2),low,high),1e-9);
        assertEquals(4,Geometry.segmentRectangleDistanceSquared(new Point(OVERWORLD,-5,5,2),new Point(OVERWORLD,15,5,2),low,high),1e-9);
        assertEquals(3,Geometry.segmentRectangleDistanceSquared(new Point(OVERWORLD,-2,-2,1),new Point(OVERWORLD,-1,-1,1),low,high),1e-9);
        assertPoint(p(5,5),Geometry.closestOnRectangle(new Point(OVERWORLD,5,70,5),p(10,10),p(0,0)));
        assertEquals(36,Geometry.rectangleDistanceSquared(p(0,0),p(10,10),new Point(OVERWORLD,5,70,5),new Point(OVERWORLD,15,70,15)),1e-9);
        assertEquals(25,Geometry.segmentRectangleDistanceSquared(p(0,0),p(10,0),p(5,5),p(5,5)),1e-9);
        assertEquals(Double.POSITIVE_INFINITY,Geometry.segmentRectangleDistanceSquared(p(0,0),p(10,0),n(0,0),n(10,0)));
    }

    @Test void strictDeniesTargetOutsideEveryRegion() {
        var engine = engine(List.of(), List.of(road("r",p(0,0),p(100,0))),
                List.of(new Station("s","r",p(0,0),true)), List.of());
        assertTrue(engine.resolve(p(0,0),p(50,50),true).isEmpty());
    }

    @Test void invalidStationDoesNotProvideAccess() {
        var invalid = new Station("s","r",p(0,10),true);
        var engine = engine(List.of(), List.of(road("r",p(0,0),p(100,0))),List.of(invalid),List.of());
        assertFalse(engine.isStationOnRoad(invalid));
        assertTrue(engine.resolve(p(0,10),p(50,0),true).isEmpty());
    }

    @Test void recordsDefensivelyCopyVerticesAndRejectNonFiniteCoordinates() {
        var vertices = new ArrayList<>(List.of(p(0,0),p(10,0),p(0,10)));
        var city = new City("a",OVERWORLD,vertices,false);
        vertices.clear();
        assertEquals(3,city.vertices().size());
        assertThrows(UnsupportedOperationException.class, () -> city.vertices().clear());
        assertThrows(IllegalArgumentException.class, () -> new Point(OVERWORLD, Double.NaN,64,0));
        assertThrows(IllegalArgumentException.class, () -> road("bad",p(0,0),n(1,1)));
    }

    @Test void normalizesExplicitCityClosureAndRejectsInvalidPolygons() {
        var square = new City("closed",OVERWORLD,List.of(p(0,0),p(10,0),p(10,10),p(0,10),p(0,0)),false);
        assertEquals(4,square.vertices().size());
        assertThrows(IllegalArgumentException.class, () ->
                new City("line",OVERWORLD,List.of(p(0,0),p(5,0),p(10,0)),false));
        assertThrows(IllegalArgumentException.class, () ->
                new City("bowtie",OVERWORLD,List.of(p(0,0),p(10,10),p(10,0),p(0,10)),false));
    }

    @Test void concaveCityDoesNotContainItsMissingCorner() {
        var lShape=new City("L",OVERWORLD,List.of(p(0,0),p(10,0),p(10,3),p(3,3),p(3,10),p(0,10)),false);
        var engine=engine(List.of(lShape),List.of(),List.of(),List.of());
        assertFalse(Geometry.contains(lShape,p(9,9)));
        var destination=engine.resolve(p(1,1),p(9,9),false).orElseThrow();
        assertEquals(36,Geometry.distanceSquared(destination,p(9,9)),1e-8);
        assertTrue(Geometry.contains(lShape,destination));
    }

    @Test void slopedRoadUsesThreeDimensionalProjection() {
        var slope=road("slope",p(0,0),new Point(OVERWORLD,10,74,0));
        assertPoint(new Point(OVERWORLD,5,69,0),Geometry.closestOnRoad(new Point(OVERWORLD,10,64,0),slope));
    }

    @Test void strictNearbyStationDoesNotBridgeAnOverlappingUnconnectedCity() {
        var engine=engine(List.of(city("a",0,0,10,10),city("b",8,0,30,10)),
                List.of(road("a-road",p(1,1),p(9,1)),road("b-road",p(28,5),p(29,5))),
                List.of(new Station("a-station","a-road",p(9,1),true),new Station("b-station","b-road",p(29,5),true)),List.of());
        // The first road actually crosses both city footprints, so it joins them by the road rule.
        assertPoint(p(29,5),engine.resolve(p(9,1),p(29,5),true).orElseThrow());
        var separate=engine(List.of(city("a",0,0,10,10),city("b",8,0,30,10)),
                List.of(road("a-road",p(1,1),p(7,1)),road("b-road",p(28,5),p(29,5))),
                List.of(new Station("a-station","a-road",p(7,1),true),new Station("b-station","b-road",p(29,5),true)),List.of());
        assertPoint(p(7,1),separate.resolve(p(8.5,1),p(29,5),true).orElseThrow());
    }

    @Test void spatialIndexHandlesNegativeCoordinatesAndOverflowSegments() {
        var engine=engine(List.of(),List.of(road("long",p(-100_000,0),p(100_000,0)),
                road("local",p(-100,1),p(-100,10))),List.of(),List.of());
        assertPoint(p(-100,10),engine.resolve(p(-500,0),p(-100,20),false).orElseThrow());
    }

    @Test void stationMembershipAcceptsToleranceButRejectsWrongDimensions() {
        var engine=engine(List.of(),List.of(road("r",p(0,0),p(10,0))),List.of(),List.of());
        assertTrue(engine.isStationOnRoad(new Station("s","r",p(5,.1),true)));
        assertFalse(engine.isStationOnRoad(new Station("s","r",p(5,.11),true)));
        assertFalse(engine.isStationOnRoad(new Station("s","r",n(5,0),true)));
    }

    @Test void largeLinearNetworkRemainsConnected() { assertLargeNetwork(10_000); }

    private static void assertLargeNetwork(int count) {
        var roads=new ArrayList<Road>(count);
        for(int i=0;i<count;i++) roads.add(road("r"+i,p(i*2,0),p(i*2+1,0)));
        var engine=engine(List.of(),roads,List.of(),List.of());
        assertPoint(p(count*2-1,0),engine.resolve(p(0,0),p(count*2+100,0),false).orElseThrow());
    }

    /** Allows the pure-Java suite to run while Loom downloads game dependencies. */
    public static void main(String[] args) throws Exception {
        int failures = 0, total = 0;
        var instance = new GraphEngineTest();
        for (var method : GraphEngineTest.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Test.class)) continue;
            total++;
            try { method.invoke(instance); }
            catch (InvocationTargetException ex) {
                failures++;
                System.err.println(method.getName()+": "+ex.getCause());
            }
        }
        System.out.println("GraphEngineTest: "+total+" tests, "+failures+" failures");
        if (failures != 0) throw new AssertionError("Graph tests failed");
        if(args.length>0 && args[0].equals("--scale")) {
            long start=System.nanoTime();
            assertLargeNetwork(100_000);
            System.out.printf("100,000-road graph construction + route: %.3f seconds%n",(System.nanoTime()-start)/1e9);
        }
    }
}
