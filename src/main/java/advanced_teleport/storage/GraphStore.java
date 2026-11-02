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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.function.Function;

/** One authoritative world graph or client cache. All graph changes commit as one transaction. */
public final class GraphStore implements AutoCloseable {
    private static final int SCHEMA_VERSION = 2;
    private final Connection connection;
    private Snapshot saved;
    private Map<String,Map<String,Long>> ordinals=Map.of();

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
            if (version < 0 || version > SCHEMA_VERSION) {
                throw new SQLException("Unsupported ATP database schema " + version + "; expected " + SCHEMA_VERSION);
            }
            if (version == SCHEMA_VERSION) return;
            connection.setAutoCommit(false);
            try {
                if(version==1) {
                    for(String end:List.of("from","to"))for(String axis:List.of("x","y","z"))
                        statement.execute("ALTER TABLE portals ADD COLUMN "+end+"_extent_"+axis+" REAL");
                    statement.executeUpdate("UPDATE portals SET from_extent_x=from_x,from_extent_y=from_y,from_extent_z=from_z,to_extent_x=to_x,to_extent_y=to_y,to_extent_z=to_z");
                    statement.execute("PRAGMA user_version="+SCHEMA_VERSION);
                    connection.commit();
                    return;
                }
                statement.execute("CREATE TABLE metadata (singleton INTEGER PRIMARY KEY CHECK(singleton=1), revision INTEGER NOT NULL)");
                statement.execute("INSERT INTO metadata VALUES (1,0)");
                statement.execute("CREATE TABLE cities (id TEXT PRIMARY KEY, dimension TEXT NOT NULL, protected INTEGER NOT NULL, ordinal INTEGER NOT NULL)");
                statement.execute("CREATE TABLE city_vertices (owner_id TEXT NOT NULL REFERENCES cities(id) ON DELETE CASCADE, ordinal INTEGER NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, PRIMARY KEY(owner_id,ordinal))");
                statement.execute("CREATE TABLE roads (id TEXT PRIMARY KEY, dimension TEXT NOT NULL, kind TEXT NOT NULL, protected INTEGER NOT NULL, ordinal INTEGER NOT NULL)");
                statement.execute("CREATE TABLE road_points (owner_id TEXT NOT NULL REFERENCES roads(id) ON DELETE CASCADE, ordinal INTEGER NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, PRIMARY KEY(owner_id,ordinal))");
                // Protected stations may remain recorded when an automatic road is removed; the graph excludes orphans.
                statement.execute("CREATE TABLE stations (id TEXT PRIMARY KEY, road_id TEXT NOT NULL, dimension TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, protected INTEGER NOT NULL, ordinal INTEGER NOT NULL)");
                statement.execute("CREATE TABLE portals (id TEXT PRIMARY KEY, from_dimension TEXT NOT NULL, from_x REAL NOT NULL, from_y REAL NOT NULL, from_z REAL NOT NULL, to_dimension TEXT NOT NULL, to_x REAL NOT NULL, to_y REAL NOT NULL, to_z REAL NOT NULL, protected INTEGER NOT NULL, ordinal INTEGER NOT NULL, from_extent_x REAL NOT NULL, from_extent_y REAL NOT NULL, from_extent_z REAL NOT NULL, to_extent_x REAL NOT NULL, to_extent_y REAL NOT NULL, to_extent_z REAL NOT NULL)");
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
            Map<String,Map<String,Long>> loadedOrdinals=new HashMap<>();
            for(String table:List.of("cities","roads","stations","portals"))loadedOrdinals.put(table,new HashMap<>());
            Map<String, List<double[]>> cityPoints = points("city_vertices");
            Map<String, List<double[]>> roadPoints = points("road_points");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT * FROM cities ORDER BY ordinal")) {
                while (result.next()) {
                    String id = result.getString("id");
                    loadedOrdinals.get("cities").put(id,result.getLong("ordinal"));
                    String dimension = result.getString("dimension");
                    cities.add(new City(id, dimension, expand(cityPoints.get(id), dimension), result.getBoolean("protected")));
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT * FROM roads ORDER BY ordinal")) {
                while (result.next()) {
                    String id = result.getString("id");
                    loadedOrdinals.get("roads").put(id,result.getLong("ordinal"));
                    String dimension = result.getString("dimension");
                    roads.add(new Road(id, dimension, expand(roadPoints.get(id), dimension),
                            RoadKind.valueOf(result.getString("kind")), result.getBoolean("protected")));
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT * FROM stations ORDER BY ordinal")) {
                while (result.next()) {
                    loadedOrdinals.get("stations").put(result.getString("id"),result.getLong("ordinal"));
                    stations.add(new Station(result.getString("id"), result.getString("road_id"),
                        new Point(result.getString("dimension"), result.getDouble("x"), result.getDouble("y"), result.getDouble("z")), result.getBoolean("protected")));
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT * FROM portals ORDER BY ordinal")) {
                while (result.next()) {
                    loadedOrdinals.get("portals").put(result.getString("id"),result.getLong("ordinal"));
                    portals.add(new PortalLink(result.getString("id"),
                        new Point(result.getString("from_dimension"), result.getDouble("from_x"), result.getDouble("from_y"), result.getDouble("from_z")),
                        new Point(result.getString("to_dimension"), result.getDouble("to_x"), result.getDouble("to_y"), result.getDouble("to_z")), result.getBoolean("protected"),
                        new Point(result.getString("from_dimension"),result.getDouble("from_extent_x"),result.getDouble("from_extent_y"),result.getDouble("from_extent_z")),
                        new Point(result.getString("to_dimension"),result.getDouble("to_extent_x"),result.getDouble("to_extent_y"),result.getDouble("to_extent_z"))));
                }
            }
            saved=new Snapshot(revision, cities, roads, stations, portals);
            ordinals=loadedOrdinals;
            return saved;
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
        Snapshot before=saved==null?load():saved;
        if(before.equals(snapshot))return;
        var cities=plan("cities",before.cities(),snapshot.cities(),City::id);
        var roads=plan("roads",before.roads(),snapshot.roads(),Road::id);
        var stations=plan("stations",before.stations(),snapshot.stations(),Station::id);
        var portals=plan("portals",before.portals(),snapshot.portals(),PortalLink::id);
        connection.setAutoCommit(false);
        try {
            removeMissing("stations",before.stations(),snapshot.stations(),Station::id);
            removeMissing("portals",before.portals(),snapshot.portals(),PortalLink::id);
            removeMissing("cities",before.cities(),snapshot.cities(),City::id);
            removeMissing("roads",before.roads(),snapshot.roads(),Road::id);
            writeCities(cities.upserts());writeOrder("cities",cities.reordered());
            writeRoads(roads.upserts());writeOrder("roads",roads.reordered());
            writeStations(stations.upserts());writeOrder("stations",stations.reordered());
            writePortals(portals.upserts());writeOrder("portals",portals.reordered());
            try (PreparedStatement statement = connection.prepareStatement("UPDATE metadata SET revision=? WHERE singleton=1")) {
                statement.setLong(1, snapshot.revision());
                if (statement.executeUpdate() != 1) throw new SQLException("ATP graph metadata is missing");
            }
            connection.commit();
            saved=snapshot;
            ordinals=Map.of("cities",cities.ordinals(),"roads",roads.ordinals(),"stations",stations.ordinals(),"portals",portals.ordinals());
        } catch (SQLException | RuntimeException exception) {
            try { connection.rollback(); } catch (SQLException rollbackFailure) { exception.addSuppressed(rollbackFailure); }
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private <T> void removeMissing(String table,List<T> before,List<T> after,Function<T,String> id)throws SQLException {
        var remaining=new HashSet<String>();after.forEach(value->remaining.add(id.apply(value)));
        try(var statement=connection.prepareStatement("DELETE FROM "+table+" WHERE id=?")) {
            for(T value:before)if(!remaining.contains(id.apply(value))){statement.setString(1,id.apply(value));statement.addBatch();}
            statement.executeBatch();
        }
    }
    private record Indexed<T>(T value,long ordinal){}
    private record Rank(String id,long ordinal){}
    private record Plan<T>(List<Indexed<T>> upserts,List<Rank> reordered,Map<String,Long> ordinals){}

    /** Deletion preserves survivor ordinals; appending does not rewrite earlier records. */
    private <T> Plan<T> plan(String table,List<T> before,List<T> after,Function<T,String> id) {
        Map<String,T> old=new LinkedHashMap<>();before.forEach(value->old.put(id.apply(value),value));
        var desiredIds=after.stream().map(id).toList();var remaining=new HashSet<>(desiredIds);
        var natural=new ArrayList<String>();
        old.keySet().stream().filter(remaining::contains).forEach(natural::add);
        desiredIds.stream().filter(key->!old.containsKey(key)).forEach(natural::add);
        Map<String,Long> previous=ordinals.getOrDefault(table,Map.of()),next=new HashMap<>();
        boolean preserve=natural.equals(desiredIds);
        long last=-1024;
        if(preserve) {
            for(String key:desiredIds)if(old.containsKey(key)) {
                Long rank=previous.get(key);
                if(rank==null){preserve=false;break;}
                next.put(key,rank);last=Math.max(last,rank);
            }
            if(preserve)try {
                for(String key:desiredIds)if(!old.containsKey(key)){
                    last=Math.addExact(last,1024L);next.put(key,last);
                }
            }catch(ArithmeticException overflow){preserve=false;}
        }
        if(!preserve){next.clear();for(int i=0;i<desiredIds.size();i++)next.put(desiredIds.get(i),i*1024L);}
        var upserts=new ArrayList<Indexed<T>>();var reordered=new ArrayList<Rank>();
        for(T value:after){
            String key=id.apply(value);long rank=next.get(key);
            if(!value.equals(old.get(key)))upserts.add(new Indexed<>(value,rank));
            else if(!java.util.Objects.equals(previous.get(key),rank))reordered.add(new Rank(key,rank));
        }
        return new Plan<>(upserts,reordered,Map.copyOf(next));
    }
    private void writeOrder(String table,List<Rank> ranks)throws SQLException {
        try(var statement=connection.prepareStatement("UPDATE "+table+" SET ordinal=? WHERE id=?")){
            for(Rank rank:ranks){statement.setLong(1,rank.ordinal());statement.setString(2,rank.id());statement.addBatch();}
            statement.executeBatch();
        }
    }

    private void writeCities(List<Indexed<City>> cities) throws SQLException {
        try (PreparedStatement entity = connection.prepareStatement("INSERT INTO cities VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET dimension=excluded.dimension,protected=excluded.protected,ordinal=excluded.ordinal");
             PreparedStatement clear = connection.prepareStatement("DELETE FROM city_vertices WHERE owner_id=?");
             PreparedStatement vertex = connection.prepareStatement("INSERT INTO city_vertices VALUES (?,?,?,?,?)")) {
            for (Indexed<City> item : cities) {
                City city=item.value();
                entity.setString(1, city.id());
                entity.setString(2, city.dimension());
                entity.setBoolean(3, city.protectedEntry());
                entity.setLong(4, item.ordinal());
                entity.executeUpdate();
                clear.setString(1,city.id());clear.executeUpdate();
                writePoints(vertex, city.id(), city.vertices());
            }
        }
    }

    private void writeRoads(List<Indexed<Road>> roads) throws SQLException {
        try (PreparedStatement entity = connection.prepareStatement("INSERT INTO roads VALUES (?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET dimension=excluded.dimension,kind=excluded.kind,protected=excluded.protected,ordinal=excluded.ordinal");
             PreparedStatement clear = connection.prepareStatement("DELETE FROM road_points WHERE owner_id=?");
             PreparedStatement vertex = connection.prepareStatement("INSERT INTO road_points VALUES (?,?,?,?,?)")) {
            for (Indexed<Road> item : roads) {
                Road road=item.value();
                entity.setString(1, road.id());
                entity.setString(2, road.dimension());
                entity.setString(3, road.kind().name());
                entity.setBoolean(4, road.protectedEntry());
                entity.setLong(5, item.ordinal());
                entity.executeUpdate();
                clear.setString(1,road.id());clear.executeUpdate();
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

    private void writeStations(List<Indexed<Station>> stations) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO stations VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET road_id=excluded.road_id,dimension=excluded.dimension,x=excluded.x,y=excluded.y,z=excluded.z,protected=excluded.protected,ordinal=excluded.ordinal")) {
            for (Indexed<Station> item : stations) {
                Station station=item.value();
                statement.setString(1, station.id());
                statement.setString(2, station.roadId());
                point(statement, 3, station.position());
                statement.setBoolean(7, station.protectedEntry());
                statement.setLong(8, item.ordinal());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void writePortals(List<Indexed<PortalLink>> portals) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO portals VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET from_dimension=excluded.from_dimension,from_x=excluded.from_x,from_y=excluded.from_y,from_z=excluded.from_z,to_dimension=excluded.to_dimension,to_x=excluded.to_x,to_y=excluded.to_y,to_z=excluded.to_z,protected=excluded.protected,ordinal=excluded.ordinal,from_extent_x=excluded.from_extent_x,from_extent_y=excluded.from_extent_y,from_extent_z=excluded.from_extent_z,to_extent_x=excluded.to_extent_x,to_extent_y=excluded.to_extent_y,to_extent_z=excluded.to_extent_z")) {
            for (Indexed<PortalLink> item : portals) {
                PortalLink portal=item.value();
                statement.setString(1, portal.id());
                point(statement, 2, portal.first());
                point(statement, 6, portal.second());
                statement.setBoolean(10, portal.protectedEntry());
                statement.setLong(11, item.ordinal());
                coordinates(statement,12,portal.firstExtent());coordinates(statement,15,portal.secondExtent());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void coordinates(PreparedStatement statement,int offset,Point point)throws SQLException {
        statement.setDouble(offset,point.x());statement.setDouble(offset+1,point.y());statement.setDouble(offset+2,point.z());
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
