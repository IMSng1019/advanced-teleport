package advanced_teleport.graph;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Immutable, dimension-aware graph values shared by the server, client and API. */
public final class GraphData {
    private GraphData() {}

    public enum RoadKind { RAIL, ICE, API }

    public record Point(String dimension, double x, double y, double z) {
        public Point {
            dimension = requireText(dimension, "dimension");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Point coordinates must be finite");
        }
    }

    public record City(String id, String dimension, List<Point> vertices, boolean protectedEntry) {
        public City {
            id = requireText(id, "city id");
            dimension = requireText(dimension, "dimension");
            vertices = checkedPoints(vertices, dimension, 3);
            Point first=vertices.get(0),last=vertices.get(vertices.size()-1);
            if (first.x()==last.x() && first.z()==last.z())
                vertices=List.copyOf(vertices.subList(0,vertices.size()-1));
            if (vertices.size()<3 || !Geometry.isSimplePolygon(vertices))
                throw new IllegalArgumentException("A city must have a nondegenerate, non-self-intersecting X/Z polygon");
        }
    }

    public record Road(String id, String dimension, List<Point> points, RoadKind kind, boolean protectedEntry) {
        public Road {
            id = requireText(id, "road id");
            dimension = requireText(dimension, "dimension");
            points = checkedPoints(points, dimension, 2);
            Objects.requireNonNull(kind, "road kind");
        }
    }

    public record Station(String id, String roadId, Point position, boolean protectedEntry) {
        public Station {
            id = requireText(id, "station id");
            roadId = requireText(roadId, "station road id");
            Objects.requireNonNull(position, "station position");
        }
    }

    public record PortalLink(String id, Point first, Point second, boolean protectedEntry) {
        public PortalLink {
            id = requireText(id, "portal id");
            Objects.requireNonNull(first, "first portal endpoint");
            Objects.requireNonNull(second, "second portal endpoint");
        }
    }

    public record Snapshot(long revision, List<City> cities, List<Road> roads,
                           List<Station> stations, List<PortalLink> portals) {
        public Snapshot {
            if (revision < 0) throw new IllegalArgumentException("Graph revision must be nonnegative");
            cities = checkedEntries(cities, City::id);
            roads = checkedEntries(roads, Road::id);
            stations = checkedEntries(stations, Station::id);
            portals = checkedEntries(portals, PortalLink::id);
        }

        public static Snapshot empty() { return new Snapshot(0, List.of(), List.of(), List.of(), List.of()); }
    }

    private static String requireText(String value, String description) {
        if (value == null || value.isBlank() || value.length() > 256)
            throw new IllegalArgumentException(description + " must contain 1-256 characters");
        return value;
    }

    private static List<Point> checkedPoints(List<Point> values, String dimension, int minimum) {
        Objects.requireNonNull(values, "vertices");
        if (values.size() < minimum)
            throw new IllegalArgumentException("At least " + minimum + " vertices are required");
        var result = List.copyOf(values);
        for (var point : result)
            if (!dimension.equals(point.dimension()))
                throw new IllegalArgumentException("All vertices must have the declared dimension");
        return result;
    }

    private static <T> List<T> checkedEntries(List<T> values, Function<T, String> id) {
        var result = List.copyOf(Objects.requireNonNull(values, "graph entries"));
        var ids = new HashSet<String>();
        for (var entry : result)
            if (!ids.add(id.apply(entry))) throw new IllegalArgumentException("Duplicate graph id: " + id.apply(entry));
        return result;
    }
}
