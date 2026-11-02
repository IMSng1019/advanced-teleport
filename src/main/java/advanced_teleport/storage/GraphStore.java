package advanced_teleport.storage;

import advanced_teleport.graph.GraphData.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One authoritative world graph or client cache. All graph changes commit as one transaction. */
public final class GraphStore implements AutoCloseable {
    private static final int SCHEMA_VERSION = 1;
    private final Connection connection;

    public GraphStore(Path database) throws IOException, SQLException {
        Path absolute = database.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
        try {
            initialize();
        } catch (SQLException | RuntimeException exception) {
            try { connection.close(); } catch (SQLException closeFailure) { exception.addSuppressed(closeFailure); }
            throw exception;
        }
    }

    private void initialize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            int version;
            try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
                version = result.getInt(1);
            }
            if (version != 0 && version != SCHEMA_VERSION) {
                throw new SQLException("Unsupported ATP database schema " + version + "; expected " + SCHEMA_VERSION);
            }
            if (version == SCHEMA_VERSION) return;
            connection.setAutoCommit(false);
            try {
                statement.execute("CREATE TABLE metadata (singleton INTEGER PRIMARY KEY CHECK(singleton=1), revision INTEGER NOT NULL)");
                statement.execute("INSERT INTO metadata VALUES (1,0)");
                statement.execute("CREATE TABLE cities (id TEXT PRIMARY KEY, dimension TEXT NOT NULL, protected INTEGER NOT NULL, ordinal INTEGER NOT NULL)");
                statement.execute("CREATE TABLE city_vertices (owner_id TEXT NOT NULL REFERENCES cities(id) ON DELETE CASCADE, ordinal INTEGER NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, PRIMARY KEY(owner_id,ordinal))");
                statement.execute("CREATE TABLE roads (id TEXT PRIMARY KEY, dimension TEXT NOT NULL, kind TEXT NOT NULL, protected INTEGER NOT NULL, ordinal INTEGER NOT NULL)");
                statement.execute("CREATE TABLE road_points (owner_id TEXT NOT NULL REFERENCES roads(id) ON DELETE CASCADE, ordinal INTEGER NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, PRIMARY KEY(owner_id,ordinal))");
                // Protected stations may remain recorded when an automatic road is removed; the graph excludes orphans.
                statement.execute("CREATE TABLE stations (id TEXT PRIMARY KEY, road_id TEXT NOT NULL, dimension TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, protected INTEGER NOT NULL, ordinal INTEGER NOT NULL)");
                statement.execute("CREATE TABLE portals (id TEXT PRIMARY KEY, from_dimension TEXT NOT NULL, from_x REAL NOT NULL, from_y REAL NOT NULL, from_z REAL NOT NULL, to_dimension TEXT NOT NULL, to_x REAL NOT NULL, to_y REAL NOT NULL, to_z REAL NOT NULL, protected INTEGER NOT NULL, ordinal INTEGER NOT NULL)");
                statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public synchronized Snapshot load() throws SQLException {
        try {
            long revision;
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT revision FROM metadata WHERE singleton=1")) {
                if (!result.next()) throw new SQLException("ATP graph metadata is missing");
                revision = result.getLong(1);
            }
            List<City> cities = new ArrayList<>();
            List<Road> roads = new ArrayList<>();
            List<Station> stations = new ArrayList<>();
            List<PortalLink> portals = new ArrayList<>();
            Map<String, List<double[]>> cityPoints = points("city_vertices");
            Map<String, List<double[]>> roadPoints = points("road_points");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT * FROM cities ORDER BY ordinal")) {
                while (result.next()) {
                    String id = result.getString("id");
                    String dimension = result.getString("dimension");
                    cities.add(new City(id, dimension, expand(cityPoints.get(id), dimension), result.getBoolean("protected")));
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT * FROM roads ORDER BY ordinal")) {
                while (result.next()) {
                    String id = result.getString("id");
                    String dimension = result.getString("dimension");
                    roads.add(new Road(id, dimension, expand(roadPoints.get(id), dimension),
                            RoadKind.valueOf(result.getString("kind")), result.getBoolean("protected")));
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT * FROM stations ORDER BY ordinal")) {
                while (result.next()) stations.add(new Station(result.getString("id"), result.getString("road_id"),
                        new Point(result.getString("dimension"), result.getDouble("x"), result.getDouble("y"), result.getDouble("z")), result.getBoolean("protected")));
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT * FROM portals ORDER BY ordinal")) {
                while (result.next()) portals.add(new PortalLink(result.getString("id"),
                        new Point(result.getString("from_dimension"), result.getDouble("from_x"), result.getDouble("from_y"), result.getDouble("from_z")),
                        new Point(result.getString("to_dimension"), result.getDouble("to_x"), result.getDouble("to_y"), result.getDouble("to_z")), result.getBoolean("protected")));
            }
            return new Snapshot(revision, cities, roads, stations, portals);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new SQLException("Invalid persisted ATP graph data", exception);
        }
    }

    private Map<String, List<double[]>> points(String table) throws SQLException {
        Map<String, List<double[]>> points = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT owner_id,x,y,z FROM " + table + " ORDER BY owner_id,ordinal")) {
            while (result.next()) points.computeIfAbsent(result.getString(1), ignored -> new ArrayList<>())
                    .add(new double[]{result.getDouble(2), result.getDouble(3), result.getDouble(4)});
        }
        return points;
    }

    private static List<Point> expand(List<double[]> coordinates, String dimension) {
        if (coordinates == null) return List.of();
        return coordinates.stream().map(point -> new Point(dimension, point[0], point[1], point[2])).toList();
    }

    public synchronized void save(Snapshot snapshot) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM stations");
                statement.executeUpdate("DELETE FROM portals");
                statement.executeUpdate("DELETE FROM cities");
                statement.executeUpdate("DELETE FROM roads");
            }
            writeCities(snapshot.cities());
            writeRoads(snapshot.roads());
            writeStations(snapshot.stations());
            writePortals(snapshot.portals());
            try (PreparedStatement statement = connection.prepareStatement("UPDATE metadata SET revision=? WHERE singleton=1")) {
                statement.setLong(1, snapshot.revision());
                if (statement.executeUpdate() != 1) throw new SQLException("ATP graph metadata is missing");
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            try { connection.rollback(); } catch (SQLException rollbackFailure) { exception.addSuppressed(rollbackFailure); }
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void writeCities(List<City> cities) throws SQLException {
        try (PreparedStatement entity = connection.prepareStatement("INSERT INTO cities VALUES (?,?,?,?)");
             PreparedStatement vertex = connection.prepareStatement("INSERT INTO city_vertices VALUES (?,?,?,?,?)")) {
            int index = 0;
            for (City city : cities) {
                entity.setString(1, city.id());
                entity.setString(2, city.dimension());
                entity.setBoolean(3, city.protectedEntry());
                entity.setInt(4, index++);
                entity.executeUpdate();
                writePoints(vertex, city.id(), city.vertices());
            }
        }
    }

    private void writeRoads(List<Road> roads) throws SQLException {
        try (PreparedStatement entity = connection.prepareStatement("INSERT INTO roads VALUES (?,?,?,?,?)");
             PreparedStatement vertex = connection.prepareStatement("INSERT INTO road_points VALUES (?,?,?,?,?)")) {
            int index = 0;
            for (Road road : roads) {
                entity.setString(1, road.id());
                entity.setString(2, road.dimension());
                entity.setString(3, road.kind().name());
                entity.setBoolean(4, road.protectedEntry());
                entity.setInt(5, index++);
                entity.executeUpdate();
                writePoints(vertex, road.id(), road.points());
            }
        }
    }

    private static void writePoints(PreparedStatement statement, String id, List<Point> points) throws SQLException {
        int index = 0;
        for (Point point : points) {
            statement.setString(1, id);
            statement.setInt(2, index++);
            statement.setDouble(3, point.x());
            statement.setDouble(4, point.y());
            statement.setDouble(5, point.z());
            statement.addBatch();
        }
        statement.executeBatch();
    }

    private void writeStations(List<Station> stations) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO stations VALUES (?,?,?,?,?,?,?,?)")) {
            int index = 0;
            for (Station station : stations) {
                statement.setString(1, station.id());
                statement.setString(2, station.roadId());
                point(statement, 3, station.position());
                statement.setBoolean(7, station.protectedEntry());
                statement.setInt(8, index++);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void writePortals(List<PortalLink> portals) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO portals VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            int index = 0;
            for (PortalLink portal : portals) {
                statement.setString(1, portal.id());
                point(statement, 2, portal.first());
                point(statement, 6, portal.second());
                statement.setBoolean(10, portal.protectedEntry());
                statement.setInt(11, index++);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void point(PreparedStatement statement, int offset, Point point) throws SQLException {
        statement.setString(offset, point.dimension());
        statement.setDouble(offset + 1, point.x());
        statement.setDouble(offset + 2, point.y());
        statement.setDouble(offset + 3, point.z());
    }

    @Override public synchronized void close() throws SQLException {
        connection.close();
    }
}
