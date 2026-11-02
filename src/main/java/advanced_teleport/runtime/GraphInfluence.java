package advanced_teleport.runtime;

import advanced_teleport.graph.*;
import advanced_teleport.graph.GraphData.*;
import java.util.*;

/** A small spatial index of geometry whose pending edits can affect one teleport. */
public final class GraphInfluence {
    private static final int CELL = 32, MAX_BUCKETS = 4096;
    private record Bucket(String dimension,long x,long z) {}
    private record Area(String dimension,double minX,double maxX,double minZ,double maxZ,java.util.function.Predicate<Point> geometry) {
        boolean contains(Point p){return dimension.equals(p.dimension())&&p.x()>=minX&&p.x()<=maxX&&p.z()>=minZ&&p.z()<=maxZ&&geometry.test(p);}
    }
    private final Map<Bucket,List<Area>> buckets=new HashMap<>();
    private final List<Area> large=new ArrayList<>();
    private final Set<String> portalIds=new HashSet<>();

    public GraphInfluence(GraphEngine graph,Point source,Point target,boolean strict,double radius) {
        add(List.of(source),radius,p->Geometry.distanceSquared(p,source)<=radius*radius);
        include(graph,source,strict,radius);
        if(strict){add(List.of(target),radius,p->Geometry.distanceSquared(p,target)<=radius*radius);include(graph,target,false,radius);}
    }
    private void include(GraphEngine graph,Point origin,boolean strict,double radius) {
        for(City city:graph.reachableCities(origin,strict))add(city.vertices(),radius,
                p->Geometry.distanceSquared(p,Geometry.closestInCity(p,city))<=radius*radius);
        for(Road road:graph.reachableRoads(origin,strict))
            for(int i=1;i<road.points().size();i++){
                Point a=road.points().get(i-1),b=road.points().get(i);
                add(List.of(a,b),radius,p->Geometry.distanceSquared(p,Geometry.closestOnSegment(p,a,b))<=radius*radius);
            }
        for(PortalLink portal:graph.reachablePortals(origin,strict)){
            portalIds.add(portal.id());
            add(List.of(portal.first(),portal.firstExtent()),radius,p->Geometry.distanceSquared(p,Geometry.closestOnRectangle(p,portal.first(),portal.firstExtent()))<=radius*radius);
            add(List.of(portal.second(),portal.secondExtent()),radius,p->Geometry.distanceSquared(p,Geometry.closestOnRectangle(p,portal.second(),portal.secondExtent()))<=radius*radius);
        }
    }
    private void add(List<Point> points,double radius,java.util.function.Predicate<Point> geometry) {
        double minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxZ=maxX;
        for(Point p:points){minX=Math.min(minX,p.x());minZ=Math.min(minZ,p.z());maxX=Math.max(maxX,p.x());maxZ=Math.max(maxZ,p.z());}
        Area area=new Area(points.get(0).dimension(),minX-radius,maxX+radius,minZ-radius,maxZ+radius,geometry);
        long x0=(long)Math.floor(area.minX/CELL),x1=(long)Math.floor(area.maxX/CELL);
        long z0=(long)Math.floor(area.minZ/CELL),z1=(long)Math.floor(area.maxZ/CELL);
        if(((double)x1-x0+1)*((double)z1-z0+1)>MAX_BUCKETS){large.add(area);return;}
        for(long x=x0;x<=x1;x++)for(long z=z0;z<=z1;z++)buckets.computeIfAbsent(new Bucket(area.dimension,x,z),k->new ArrayList<>()).add(area);
    }
    public boolean contains(Point point) {
        var candidates=buckets.getOrDefault(new Bucket(point.dimension(),(long)Math.floor(point.x()/CELL),(long)Math.floor(point.z()/CELL)),List.of());
        return candidates.stream().anyMatch(a->a.contains(point))||large.stream().anyMatch(a->a.contains(point));
    }
    public boolean containsPortal(String id){return portalIds.contains(id);}
}
