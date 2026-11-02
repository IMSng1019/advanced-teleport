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
            assertThrows(SQLException.class, () -> store.save(example(4)));
            assertEquals(before, store.load());
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
