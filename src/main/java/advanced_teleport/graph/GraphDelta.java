package advanced_teleport.graph;

import advanced_teleport.graph.GraphData.*;
import java.util.*;
import java.util.function.Function;

/** An immutable, revision-bound set of graph mutations. Unchanged geometry is not transmitted. */
public record GraphDelta(long baseRevision,long revision,Entries<City> cities,Entries<Road> roads,
                         Entries<Station> stations,Entries<PortalLink> portals) {
    public GraphDelta {
        if(baseRevision<0||revision<baseRevision)throw new IllegalArgumentException("Invalid delta revisions");
        Objects.requireNonNull(cities);Objects.requireNonNull(roads);Objects.requireNonNull(stations);Objects.requireNonNull(portals);
    }
    public record Entries<T>(List<T> upserts,List<String> removed,List<String> order) {
        public Entries {upserts=List.copyOf(upserts);removed=List.copyOf(removed);order=List.copyOf(order);}
    }
    public static GraphDelta between(Snapshot before,Snapshot after) {
        return new GraphDelta(before.revision(),after.revision(),changes(before.cities(),after.cities(),City::id),
                changes(before.roads(),after.roads(),Road::id),changes(before.stations(),after.stations(),Station::id),
                changes(before.portals(),after.portals(),PortalLink::id));
    }
    public Snapshot apply(Snapshot base) {
        if(base==null||base.revision()!=baseRevision)throw new IllegalArgumentException("Graph delta base revision mismatch");
        return new Snapshot(revision,apply(base.cities(),cities,City::id),apply(base.roads(),roads,Road::id),
                apply(base.stations(),stations,Station::id),apply(base.portals(),portals,PortalLink::id));
    }
    private static <T> Entries<T> changes(List<T> before,List<T> after,Function<T,String> id) {
        Map<String,T> old=index(before,id),updated=index(after,id);
        List<String> removed=old.keySet().stream().filter(key->!updated.containsKey(key)).toList();
        List<T> upserts=after.stream().filter(value->!value.equals(old.get(id.apply(value)))).toList();
        var natural=new LinkedHashMap<>(old);removed.forEach(natural::remove);upserts.forEach(value->natural.put(id.apply(value),value));
        List<String> desired=after.stream().map(id).toList();
        List<String> order=new ArrayList<>(natural.keySet()).equals(desired)?List.of():desired;
        return new Entries<>(upserts,removed,order);
    }
    private static <T> List<T> apply(List<T> before,Entries<T> delta,Function<T,String> id) {
        Map<String,T> result=index(before,id);
        var removals=new HashSet<String>();
        for(String key:delta.removed())if(!removals.add(key))throw new IllegalArgumentException("Duplicate delta removal");
        removals.forEach(result::remove);
        Map<String,T> additions=index(delta.upserts(),id);result.putAll(additions);
        if(delta.order().isEmpty())return List.copyOf(result.values());
        if(delta.order().size()!=result.size()||new HashSet<>(delta.order()).size()!=result.size()
                ||!result.keySet().containsAll(delta.order()))throw new IllegalArgumentException("Invalid delta ordering");
        return delta.order().stream().map(result::get).toList();
    }
    private static <T> Map<String,T> index(List<T> values,Function<T,String> id) {
        Map<String,T> result=new LinkedHashMap<>();
        for(T value:values)if(result.putIfAbsent(id.apply(value),value)!=null)throw new IllegalArgumentException("Duplicate delta id");
        return result;
    }
}
