package advanced_teleport.storage;

import advanced_teleport.graph.GraphData.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphStoreTest {
    @TempDir Path temporary;

    private Snapshot example(long revision) {
        Point start = new Point("minecraft:overworld", 0, 64, 0);
        Point finish = new Point("minecraft:overworld", 10, 64, 0);
        City city = new City("测试城市", "minecraft:overworld", List.of(start, finish,
                new Point("minecraft:overworld", 10, 64, 10)), true);
        Road road = new Road("railway", "minecraft:overworld", List.of(start, finish), RoadKind.RAIL, true);
        Station station = new Station("station", "railway", start, true);
        PortalLink portal = new PortalLink("portal", finish, new Point("minecraft:the_nether", 1, 70, 0), false);
        return new Snapshot(revision, List.of(city), List.of(road), List.of(station), List.of(portal));
    }

    @Test void completeGraphAndProtectedFlagsSurviveReopening() throws Exception {
        Path database = temporary.resolve("world/graph.sqlite");
        Snapshot snapshot = example(18);
        try (GraphStore store = new GraphStore(database)) {
            assertEquals(new Snapshot(0, List.of(), List.of(), List.of(), List.of()), store.load());
            store.save(snapshot);
        }
        try (GraphStore reopened = new GraphStore(database)) {
            assertEquals(snapshot, reopened.load());
        }
    }

    @Test void failedWriteRollsBackGraphAndRevisionTogether() throws Exception {
        Path database = temporary.resolve("graph.sqlite");
        try (GraphStore store = new GraphStore(database)) {
            Snapshot before = example(3);
            store.save(before);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER reject_city BEFORE INSERT ON cities BEGIN SELECT RAISE(ABORT, 'test failure'); END");
            }
            Snapshot changed = new Snapshot(4,List.of(new City("new-city","minecraft:overworld",before.cities().get(0).vertices(),true)),
                    before.roads(),before.stations(),before.portals());
            assertThrows(SQLException.class, () -> store.save(changed));
            assertEquals(before, store.load());
        }
    }

    @Test void changingOneRoadDoesNotRewriteUnrelatedCityRows() throws Exception {
        Path database=temporary.resolve("incremental.sqlite");
        try(GraphStore store=new GraphStore(database)) {
            Snapshot before=example(1);store.save(before);
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+database);var s=c.createStatement()) {
                s.execute("CREATE TRIGGER forbid_city_delete BEFORE DELETE ON cities BEGIN SELECT RAISE(ABORT,'unchanged city deleted'); END");
                s.execute("CREATE TRIGGER forbid_city_update BEFORE UPDATE ON cities BEGIN SELECT RAISE(ABORT,'unchanged city updated'); END");
                s.execute("CREATE TRIGGER forbid_city_insert BEFORE INSERT ON cities BEGIN SELECT RAISE(ABORT,'unchanged city inserted'); END");
            }
            Road old=before.roads().get(0);
            Road updated=new Road(old.id(),old.dimension(),List.of(old.points().get(0),new Point(old.dimension(),20,64,0)),old.kind(),true);
            Snapshot after=new Snapshot(2,before.cities(),List.of(updated),before.stations(),before.portals());
            store.save(after);
            assertEquals(after,store.load());
        }
    }

    @Test void deletingFirstRoadDoesNotTouchUnchangedSurvivorOrItsPoints()throws Exception {
        Path database=temporary.resolve("remove-first.sqlite");
        Point a=new Point("minecraft:overworld",0,64,0),b=new Point(a.dimension(),10,64,0);
        Road first=new Road("first",a.dimension(),List.of(a,b),RoadKind.API,true);
        Road keep=new Road("keep",a.dimension(),List.of(a,b),RoadKind.API,true);
        try(GraphStore store=new GraphStore(database)) {
            store.save(new Snapshot(1,List.of(),List.of(first,keep),List.of(),List.of()));
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+database);var s=c.createStatement()) {
                s.execute("CREATE TRIGGER forbid_kept_road BEFORE UPDATE ON roads WHEN OLD.id='keep' BEGIN SELECT RAISE(ABORT,'unchanged survivor updated'); END");
                s.execute("CREATE TRIGGER forbid_kept_points BEFORE DELETE ON road_points WHEN OLD.owner_id='keep' BEGIN SELECT RAISE(ABORT,'unchanged survivor points deleted'); END");
            }
            Snapshot after=new Snapshot(2,List.of(),List.of(keep),List.of(),List.of());
            store.save(after);assertEquals(after,store.load());
            // Reopening must remember the sparse stored ordinal rather than renumbering it to zero.
        }
        try(GraphStore store=new GraphStore(database)) {
            Snapshot loaded=store.load();
            store.save(new Snapshot(3,List.of(),List.of(keep,first),List.of(),List.of()));
            assertEquals(List.of(keep,first),store.load().roads());
            assertEquals(List.of(keep),loaded.roads());
        }
    }

    @Test void portalSurfacesAndListOrderSurviveIncrementalSaves() throws Exception {
        Path database=temporary.resolve("surface.sqlite");
        Point a=new Point("minecraft:overworld",0,64,0),b=new Point("minecraft:the_nether",10,70,10);
        PortalLink portal=new PortalLink("wide",a,b,false,new Point(a.dimension(),19,68,0),new Point(b.dimension(),12,74,10));
        Road road=new Road("a",a.dimension(),List.of(a,new Point(a.dimension(),10,64,0)),RoadKind.API,true);
        Road other=new Road("b",a.dimension(),List.of(a,new Point(a.dimension(),20,64,0)),RoadKind.API,true);
        Snapshot first=new Snapshot(1,List.of(),List.of(road,other),List.of(),List.of(portal));
        Snapshot reordered=new Snapshot(2,List.of(),List.of(other,road),List.of(),List.of(portal));
        try(GraphStore store=new GraphStore(database)){store.save(first);store.save(reordered);}
        try(GraphStore store=new GraphStore(database)){assertEquals(reordered,store.load());}
    }

    @Test void versionOneDatabaseMigratesPortalPointsWithoutLosingData()throws Exception {
        Path database=temporary.resolve("legacy.sqlite");
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database);var s=c.createStatement()) {
            s.execute("CREATE TABLE metadata(singleton INTEGER PRIMARY KEY,revision INTEGER)");s.execute("INSERT INTO metadata VALUES(1,7)");
            s.execute("CREATE TABLE cities(id TEXT PRIMARY KEY,dimension TEXT,protected INTEGER,ordinal INTEGER)");
            s.execute("CREATE TABLE city_vertices(owner_id TEXT,ordinal INTEGER,x REAL,y REAL,z REAL)");
            s.execute("CREATE TABLE roads(id TEXT PRIMARY KEY,dimension TEXT,kind TEXT,protected INTEGER,ordinal INTEGER)");
            s.execute("CREATE TABLE road_points(owner_id TEXT,ordinal INTEGER,x REAL,y REAL,z REAL)");
            s.execute("CREATE TABLE stations(id TEXT PRIMARY KEY,road_id TEXT,dimension TEXT,x REAL,y REAL,z REAL,protected INTEGER,ordinal INTEGER)");
            s.execute("CREATE TABLE portals(id TEXT PRIMARY KEY,from_dimension TEXT,from_x REAL,from_y REAL,from_z REAL,to_dimension TEXT,to_x REAL,to_y REAL,to_z REAL,protected INTEGER,ordinal INTEGER)");
            s.execute("INSERT INTO portals VALUES('legacy','minecraft:overworld',0,64,0,'minecraft:the_nether',10,70,10,1,0)");
            s.execute("PRAGMA user_version=1");
        }
        try(GraphStore store=new GraphStore(database)) {
            Snapshot migrated=store.load();assertEquals(7,migrated.revision());
            PortalLink portal=migrated.portals().get(0);assertTrue(portal.protectedEntry());
            assertEquals(portal.first(),portal.firstExtent());assertEquals(portal.second(),portal.secondExtent());
            store.save(new Snapshot(8,migrated.cities(),migrated.roads(),migrated.stations(),migrated.portals()));
        }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database);var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")) {
            assertEquals(2,r.getInt(1));
        }
    }

    @Test void newerSchemaIsRejectedWithoutOverwritingIt() throws Exception {
        Path database = temporary.resolve("graph.sqlite");
        try (GraphStore ignored = new GraphStore(database)) { }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=999");
        }
        assertThrows(SQLException.class, () -> new GraphStore(database));
    }
}
