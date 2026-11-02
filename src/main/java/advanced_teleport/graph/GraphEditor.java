package advanced_teleport.graph;

import advanced_teleport.graph.GraphData.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Authoritative mutation boundary. Automatic observations can never overwrite protected API data. */
public final class GraphEditor {
    private Snapshot current;
    private final GraphSettings settings;

    public GraphEditor(Snapshot initial, GraphSettings settings) {
        this.current = Objects.requireNonNull(initial, "initial snapshot");
        this.settings = Objects.requireNonNull(settings, "graph settings");
    }

    public synchronized Snapshot snapshot() { return current; }

    /** Replaces precisely the automatic candidates that the detector could fully examine. */
    public synchronized boolean applyAutomatic(List<Road> observations, Set<String> examinedIds) {
        Set<String> examined = Set.copyOf(examinedIds);
        for (String id : examined) automaticId(id);
        Map<String, Road> incoming = new LinkedHashMap<>();
        for (Road observation : observations) {
            automaticId(observation.id());
            if (!examined.contains(observation.id())) {
                throw new IllegalArgumentException("Road was not examined: " + observation.id());
            }
            Road road = new Road(observation.id(), observation.dimension(), observation.points(), observation.kind(), false);
            if (incoming.putIfAbsent(road.id(), road) != null) {
                throw new IllegalArgumentException("Duplicate automatic road: " + road.id());
            }
        }
        List<Road> roads = new ArrayList<>();
        for (Road old : current.roads()) {
            Road replacement = incoming.remove(old.id());
            if (old.protectedEntry() || !examined.contains(old.id())) roads.add(old);
            else if (replacement != null) roads.add(replacement);
        }
        roads.addAll(incoming.values());
        return commit(current.cities(), roads, keepStations(current.stations(), roads), current.portals());
    }

    public synchronized void registerCity(City city) {
        apiId(city.id());
        City protectedCity = new City(city.id(), city.dimension(), city.vertices(), true);
        commit(upsert(current.cities(), protectedCity, City::id), current.roads(), current.stations(), current.portals());
    }

    public synchronized void registerRoad(Road road) {
        apiId(road.id());
        Road protectedRoad = new Road(road.id(), road.dimension(), road.points(), road.kind(), true);
        commit(current.cities(), upsert(current.roads(), protectedRoad, Road::id), current.stations(), current.portals());
    }

    public synchronized void registerStation(Station station) {
        apiId(station.id());
        Station protectedStation = new Station(station.id(), station.roadId(), station.position(), true);
        if (!new GraphEngine(current, settings).isStationOnRoad(protectedStation)) {
            throw new IllegalArgumentException("Station must lie on its registered road (within 0.1 block)");
        }
        commit(current.cities(), current.roads(), upsert(current.stations(), protectedStation, Station::id), current.portals());
    }

    public synchronized void registerPortal(PortalLink portal) {
        apiId(portal.id());
        PortalLink protectedPortal = new PortalLink(portal.id(), portal.first(), portal.second(), true, portal.firstExtent(), portal.secondExtent());
        commit(current.cities(), current.roads(), current.stations(), upsert(current.portals(), protectedPortal, PortalLink::id));
    }

    /** Records a paired portal observed during actual travel, without granting API protection. */
    public synchronized boolean putAutoPortal(PortalLink portal) {
        automaticId(portal.id());
        if (current.portals().stream().anyMatch(old -> old.id().equals(portal.id()) && old.protectedEntry())) return false;
        PortalLink automatic = new PortalLink(portal.id(), portal.first(), portal.second(), false, portal.firstExtent(), portal.secondExtent());
        return commit(current.cities(), current.roads(), current.stations(), upsert(current.portals(), automatic, PortalLink::id));
    }

    public synchronized boolean removeAutoPortal(String id) {
        if (!id.startsWith("auto:")) return false;
        List<PortalLink> portals = current.portals().stream()
                .filter(portal -> !portal.id().equals(id) || portal.protectedEntry()).toList();
        return commit(current.cities(), current.roads(), current.stations(), portals);
    }

    /** Explicit owner/admin removal may remove protected entries. */
    public synchronized boolean remove(String type, String id) {
        Objects.requireNonNull(id, "entry id");
        return switch (type) {
            case "city" -> commit(without(current.cities(), id, City::id), current.roads(), current.stations(), current.portals());
            case "road" -> {
                List<Road> roads = without(current.roads(), id, Road::id);
                yield commit(current.cities(), roads, keepStations(current.stations(), roads), current.portals());
            }
            case "station" -> commit(current.cities(), current.roads(), without(current.stations(), id, Station::id), current.portals());
            case "portal" -> commit(current.cities(), current.roads(), current.stations(), without(current.portals(), id, PortalLink::id));
            default -> throw new IllegalArgumentException("Unknown graph entry type: " + type);
        };
    }

    private boolean commit(List<City> cities, List<Road> roads, List<Station> stations, List<PortalLink> portals) {
        if (current.cities().equals(cities) && current.roads().equals(roads)
                && current.stations().equals(stations) && current.portals().equals(portals)) return false;
        current = new Snapshot(Math.addExact(current.revision(), 1), cities, roads, stations, portals);
        return true;
    }

    private static List<Station> keepStations(List<Station> stations, List<Road> roads) {
        Set<String> roadIds = new HashSet<>();
        roads.forEach(road -> roadIds.add(road.id()));
        return stations.stream().filter(station -> station.protectedEntry() || roadIds.contains(station.roadId())).toList();
    }

    private static <T> List<T> upsert(List<T> previous, T value, Function<T, String> id) {
        List<T> result = new ArrayList<>(previous);
        String key = id.apply(value);
        for (int i = 0; i < result.size(); i++) {
            if (id.apply(result.get(i)).equals(key)) {
                result.set(i, value);
                return result;
            }
        }
        result.add(value);
        return result;
    }

    private static <T> List<T> without(List<T> values, String key, Function<T, String> id) {
        return values.stream().filter(value -> !id.apply(value).equals(key)).toList();
    }

    private static void apiId(String id) {
        if (id.startsWith("auto:")) throw new IllegalArgumentException("The auto: namespace is reserved for detected infrastructure");
    }

    private static void automaticId(String id) {
        if (!id.startsWith("auto:")) throw new IllegalArgumentException("Automatic graph entries require an auto: id");
    }
}
