package advanced_teleport.graph;

import advanced_teleport.graph.GraphData.*;
import java.util.*;

/** A snapshot-derived graph. It never joins cities merely because their footprints overlap. */
public final class GraphEngine {
    private static final double EPS = 1e-9;
    private final Snapshot snapshot;
    private final GraphSettings settings;
    private final Map<String,Integer> roadIndices = new HashMap<>();
    private final List<Station> validStations;
    private final UnionFind components;
    private final Bounds[] cityBounds;
    private final Bounds[] roadBounds;
    private final Map<String,PortalAttachment> portalAttachments=new HashMap<>();
    private static final Comparator<Station> STATION_ORDER=Comparator
            .comparing((Station station)->station.position().dimension())
            .thenComparingDouble(station->station.position().x())
            .thenComparingDouble(station->station.position().y())
            .thenComparingDouble(station->station.position().z()).thenComparing(Station::id);

    public GraphEngine(Snapshot snapshot, GraphSettings settings) {
        this.snapshot=Objects.requireNonNull(snapshot);
        this.settings=Objects.requireNonNull(settings);
        components=new UnionFind(snapshot.cities().size()+snapshot.roads().size());
        cityBounds=snapshot.cities().stream().map(c -> Bounds.of(c.vertices())).toArray(Bounds[]::new);
        roadBounds=snapshot.roads().stream().map(r -> Bounds.of(r.points())).toArray(Bounds[]::new);
        for (int i=0;i<snapshot.roads().size();i++) roadIndices.put(snapshot.roads().get(i).id(),i);
        connectCities();
        RoadIndex index=connectRoads();
        connectPortals(index);
        validStations=snapshot.stations().stream().filter(this::isStationOnRoad)
                .sorted(STATION_ORDER).toList();
    }

    public Optional<Point> resolve(Point source, Point target, boolean strict) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(target);
        Set<Integer> sourceComponents=strict ? stationComponentsAt(source) : componentsAt(source);
        if (sourceComponents.isEmpty()) return Optional.empty();
        if (strict) return resolveStrict(sourceComponents,target);
        return closestInComponents(target,sourceComponents);
    }

    /** API and network adapters must call this before accepting a station. */
    public boolean isStationOnRoad(Station station) {
        Integer index=roadIndices.get(station.roadId());
        return index != null && Geometry.distanceToRoadSquared(station.position(),snapshot.roads().get(index)) <= .01+EPS;
    }

    /** Immutable geometry views for checking whether a pending local edit can affect this journey. */
    public List<Road> reachableRoads(Point source,boolean strict) {
        Set<Integer> roots=sourceComponents(source,strict);
        var result=new ArrayList<Road>();
        for(int i=0;i<snapshot.roads().size();i++)if(roots.contains(roadComponent(i)))result.add(snapshot.roads().get(i));
        return List.copyOf(result);
    }

    public List<City> reachableCities(Point source,boolean strict) {
        Set<Integer> roots=sourceComponents(source,strict);
        var result=new ArrayList<City>();
        for(int i=0;i<snapshot.cities().size();i++)if(roots.contains(components.find(i)))result.add(snapshot.cities().get(i));
        return List.copyOf(result);
    }

    public List<PortalLink> reachablePortals(Point source,boolean strict) {
        Set<Integer> roots=sourceComponents(source,strict);
        return snapshot.portals().stream().filter(portal->{
            PortalAttachment attachment=portalAttachments.get(portal.id());
            return attachment!=null && attachment.roadNodes().stream().anyMatch(node->roots.contains(components.find(node)));
        }).toList();
    }

    private Set<Integer> sourceComponents(Point source,boolean strict) {
        return strict?stationComponentsAt(source):componentsAt(source);
    }

    private Optional<Point> resolveStrict(Set<Integer> sourceComponents, Point target) {
        Set<Integer> targetComponents=componentsAt(target);
        if (targetComponents.isEmpty()) return Optional.empty();
        Station best=null;
        double bestRegionDistance=Double.POSITIVE_INFINITY,bestTargetDistance=Double.POSITIVE_INFINITY;
        Map<Integer,PortalDistances> routes=new HashMap<>();
        for (Station station:validStations) {
            int component=roadComponent(roadIndices.get(station.roadId()));
            if (!sourceComponents.contains(component)) continue;
            // Euclidean distance is meaningful only within one dimension. Portal links can make
            // target-component geometry available in the station's dimension; otherwise exclude it.
            Optional<Point> nearest=targetComponents.contains(roadComponent(roadIndices.get(station.roadId())))
                    ? Optional.of(station.position()) : closestInComponents(station.position(),targetComponents);
            if (nearest.isEmpty()) continue;
            double regionDistance=Geometry.distanceSquared(station.position(),nearest.get());
            double targetDistance=routes.computeIfAbsent(component,root->new PortalDistances(root,target)).from(station.position());
            if (best==null || regionDistance<bestRegionDistance-EPS ||
                    (sameDistance(regionDistance,bestRegionDistance) && (targetDistance<bestTargetDistance-EPS
                            || sameDistance(targetDistance,bestTargetDistance)&&STATION_ORDER.compare(station,best)<0))) {
                best=station;bestRegionDistance=regionDistance;bestTargetDistance=targetDistance;
            }
        }
        return best==null ? Optional.empty() : Optional.of(best.position());
    }

    private static boolean sameDistance(double a,double b) {
        return Double.compare(a,b)==0||Math.abs(a-b)<=EPS;
    }

    private Optional<Point> closestInComponents(Point target, Set<Integer> roots) {
        Point best=null;
        double bestDistance=Double.POSITIVE_INFINITY;
        for (int i=0;i<snapshot.cities().size();i++) {
            City city=snapshot.cities().get(i);
            if (!city.dimension().equals(target.dimension()) || !roots.contains(components.find(i))) continue;
            if (cityBounds[i].distanceSquared(target,true)>=bestDistance) continue;
            Point candidate=Geometry.closestInCity(target,city);
            double distance=Geometry.distanceSquared(target,candidate);
            if (distance==0) return Optional.of(candidate);
            if (distance<bestDistance) { best=candidate;bestDistance=distance; }
        }
        for (int i=0;i<snapshot.roads().size();i++) {
            Road road=snapshot.roads().get(i);
            if (!road.dimension().equals(target.dimension()) || !roots.contains(roadComponent(i))) continue;
            if (roadBounds[i].distanceSquared(target,false)>=bestDistance) continue;
            Point candidate=Geometry.closestOnRoad(target,road);
            double distance=Geometry.distanceSquared(target,candidate);
            if (distance==0) return Optional.of(candidate);
            if (distance<bestDistance) { best=candidate;bestDistance=distance; }
        }
        return Optional.ofNullable(best);
    }

    private Set<Integer> componentsAt(Point point) {
        Set<Integer> result=new HashSet<>();
        for (int i=0;i<snapshot.cities().size();i++)
            if (cityBounds[i].containsXZ(point) && Geometry.contains(snapshot.cities().get(i),point))
                result.add(components.find(i));
        double distance=settings.roadAccessDistance();
        for (int i=0;i<snapshot.roads().size();i++)
            if (roadBounds[i].near(point,distance) &&
                    Geometry.distanceToRoadSquared(point,snapshot.roads().get(i))<=distance*distance+EPS)
                result.add(roadComponent(i));
        return result;
    }

    private Set<Integer> stationComponentsAt(Point source) {
        Set<Integer> result=new HashSet<>();
        double distance=settings.stationAccessDistance();
        for (Station station:validStations)
            if (Geometry.distanceSquared(source,station.position())<=distance*distance+EPS)
                result.add(roadComponent(roadIndices.get(station.roadId())));
        return result;
    }

    private void connectCities() {
        for (int c=0;c<snapshot.cities().size();c++) {
            City city=snapshot.cities().get(c);
            for (int r=0;r<snapshot.roads().size();r++) {
                Road road=snapshot.roads().get(r);
                if (city.dimension().equals(road.dimension()) && cityBounds[c].intersectsXZ(roadBounds[r]) &&
                        Geometry.roadIntersectsCity(road,city)) components.union(c,roadNode(r));
            }
        }
    }

    private RoadIndex connectRoads() {
        double distance=settings.roadConnectionDistance();
        RoadIndex index=new RoadIndex(Math.max(16,Math.min(1024,distance*2+1)));
        for (int r=0;r<snapshot.roads().size();r++) {
            Road road=snapshot.roads().get(r);
            for (int i=1;i<road.points().size();i++) {
                Segment segment=new Segment(r,road.points().get(i-1),road.points().get(i));
                for (Segment other:index.query(segment.bounds().expanded(distance),road.dimension())) {
                    if (components.find(roadNode(r))==components.find(roadNode(other.road()))) continue;
                    if (segment.bounds().expanded(distance).intersects(other.bounds()) &&
                            Geometry.segmentDistanceSquared(segment.a(),segment.b(),other.a(),other.b())<=distance*distance+EPS)
                        components.union(roadNode(r),roadNode(other.road()));
                }
                index.add(segment);
            }
        }
        return index;
    }

    private void connectPortals(RoadIndex index) {
        double distance=settings.portalConnectionDistance();
        for (PortalLink portal:snapshot.portals()) {
            int firstNode=-1;
            var firstRoads=new HashSet<Integer>();var secondRoads=new HashSet<Integer>();
            List<Surface> surfaces=List.of(new Surface(portal.first(),portal.firstExtent()),new Surface(portal.second(),portal.secondExtent()));
            for (int endpoint=0;endpoint<2;endpoint++) {
                Surface surface=surfaces.get(endpoint);
                Set<Integer> attachment=endpoint==0?firstRoads:secondRoads;
                for (Segment segment:index.query(Bounds.of(List.of(surface.anchor(),surface.extent())).expanded(distance),surface.anchor().dimension())) {
                    if (Geometry.segmentRectangleDistanceSquared(segment.a(),segment.b(),surface.anchor(),surface.extent())>distance*distance+EPS) continue;
                    int node=roadNode(segment.road());
                    attachment.add(node);
                    if (firstNode<0) firstNode=node;
                    else components.union(firstNode,node);
                }
            }
            portalAttachments.put(portal.id(),new PortalAttachment(Set.copyOf(firstRoads),Set.copyOf(secondRoads)));
        }
    }

    private record PortalAttachment(Set<Integer> first,Set<Integer> second) {
        Set<Integer> roadNodes() {var nodes=new HashSet<>(first);nodes.addAll(second);return nodes;}
        boolean spansComponent(int root,UnionFind components) {
            return first.stream().anyMatch(node->components.find(node)==root)
                    &&second.stream().anyMatch(node->components.find(node)==root);
        }
    }

    private record Surface(Point anchor,Point extent) {
        double distance(Point point) {
            if(!point.dimension().equals(anchor.dimension()))return Double.POSITIVE_INFINITY;
            return Math.sqrt(Geometry.distanceSquared(point,Geometry.closestOnRectangle(point,anchor,extent)));
        }
        double distance(Surface other) {
            return Math.sqrt(Geometry.rectangleDistanceSquared(anchor,extent,other.anchor,other.extent));
        }
    }

    /**
     * A geometric cross-dimension tie metric: straight-line distances within each dimension,
     * portal surfaces as transit zones, and zero cost for crossing a registered paired portal.
     * It is deliberately not rail travel time; primary strict-mode ordering remains distance
     * to the destination component. Only portal pairs served by this component may be used.
     */
    private final class PortalDistances {
        private final Point target;
        private final List<Surface> surfaces=new ArrayList<>();
        private final double[] distance;

        PortalDistances(int component,Point target) {
            this.target=target;
            for(PortalLink portal:snapshot.portals()) {
                PortalAttachment attachment=portalAttachments.get(portal.id());
                if(attachment==null||!attachment.spansComponent(component,components))continue;
                surfaces.add(new Surface(portal.first(),portal.firstExtent()));
                surfaces.add(new Surface(portal.second(),portal.secondExtent()));
            }
            distance=new double[surfaces.size()];
            boolean[] settled=new boolean[surfaces.size()];
            for(int i=0;i<distance.length;i++)distance[i]=surfaces.get(i).distance(target);
            for(int step=0;step<distance.length;step++) {
                int nearest=-1;
                for(int i=0;i<distance.length;i++)
                    if(!settled[i]&&(nearest<0||distance[i]<distance[nearest]))nearest=i;
                if(nearest<0||!Double.isFinite(distance[nearest]))break;
                settled[nearest]=true;
                for(int i=0;i<distance.length;i++)if(!settled[i]) {
                    double cost=i==(nearest^1)?0:surfaces.get(nearest).distance(surfaces.get(i));
                    distance[i]=Math.min(distance[i],distance[nearest]+cost);
                }
            }
        }

        double from(Point station) {
            double best=Math.sqrt(Geometry.distanceSquared(station,target));
            for(int i=0;i<surfaces.size();i++)best=Math.min(best,surfaces.get(i).distance(station)+distance[i]);
            return best;
        }
    }

    private int roadNode(int index) { return snapshot.cities().size()+index; }
    private int roadComponent(int index) { return components.find(roadNode(index)); }

    private static final class UnionFind {
        private final int[] parent, rank;
        UnionFind(int size) { parent=new int[size];rank=new int[size];for(int i=0;i<size;i++)parent[i]=i; }
        int find(int node) { while(node!=parent[node]) {parent[node]=parent[parent[node]];node=parent[node];}return node; }
        void union(int a,int b) { a=find(a);b=find(b);if(a==b)return;if(rank[a]<rank[b])parent[a]=b;else {parent[b]=a;if(rank[a]==rank[b])rank[a]++;} }
    }

    private record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
        static Bounds at(Point p) { return new Bounds(p.x(),p.y(),p.z(),p.x(),p.y(),p.z()); }
        static Bounds of(List<Point> points) {
            double ax=Double.POSITIVE_INFINITY,ay=ax,az=ax,bx=Double.NEGATIVE_INFINITY,by=bx,bz=bx;
            for(Point p:points) {ax=Math.min(ax,p.x());ay=Math.min(ay,p.y());az=Math.min(az,p.z());bx=Math.max(bx,p.x());by=Math.max(by,p.y());bz=Math.max(bz,p.z());}
            return new Bounds(ax,ay,az,bx,by,bz);
        }
        Bounds expanded(double d) {return new Bounds(minX-d,minY-d,minZ-d,maxX+d,maxY+d,maxZ+d);}
        boolean intersects(Bounds b) {return intersectsXZ(b)&&minY<=b.maxY&&maxY>=b.minY;}
        boolean intersectsXZ(Bounds b) {return minX<=b.maxX&&maxX>=b.minX&&minZ<=b.maxZ&&maxZ>=b.minZ;}
        boolean containsXZ(Point p) {return p.x()>=minX-EPS&&p.x()<=maxX+EPS&&p.z()>=minZ-EPS&&p.z()<=maxZ+EPS;}
        boolean near(Point p,double d) {return p.x()>=minX-d&&p.x()<=maxX+d&&p.y()>=minY-d&&p.y()<=maxY+d&&p.z()>=minZ-d&&p.z()<=maxZ+d;}
        double distanceSquared(Point p,boolean ignoreY) {
            double dx=Math.max(Math.max(minX-p.x(),0),p.x()-maxX);
            double dy=ignoreY ? 0 : Math.max(Math.max(minY-p.y(),0),p.y()-maxY);
            double dz=Math.max(Math.max(minZ-p.z(),0),p.z()-maxZ);
            return dx*dx+dy*dy+dz*dz;
        }
    }
    private record Segment(int road,Point a,Point b,Bounds bounds) {
        Segment(int road,Point a,Point b) {this(road,a,b,Bounds.of(List.of(a,b)));}
    }
    private record Cell(String dimension,long x,long y,long z) {}

    /** Spatial broadphase keeps usual short detected road segments out of an all-pairs scan. */
    private static final class RoadIndex {
        private static final int MAX_CELLS=2048;
        private final double cellSize;
        private final Map<Cell,List<Segment>> cells=new HashMap<>();
        private final Map<String,List<Segment>> all=new HashMap<>(),overflow=new HashMap<>();
        RoadIndex(double cellSize) {this.cellSize=cellSize;}
        void add(Segment segment) {
            String dimension=segment.a().dimension();
            all.computeIfAbsent(dimension,k->new ArrayList<>()).add(segment);
            List<Cell> keys=keys(segment.bounds(),dimension);
            if(keys==null)overflow.computeIfAbsent(dimension,k->new ArrayList<>()).add(segment);
            else for(Cell key:keys)cells.computeIfAbsent(key,k->new ArrayList<>()).add(segment);
        }
        Collection<Segment> query(Bounds bounds,String dimension) {
            List<Cell> keys=keys(bounds,dimension);
            if(keys==null)return all.getOrDefault(dimension,List.of());
            Set<Segment> result=new HashSet<>(overflow.getOrDefault(dimension,List.of()));
            for(Cell key:keys)result.addAll(cells.getOrDefault(key,List.of()));
            return result;
        }
        private List<Cell> keys(Bounds b,String dimension) {
            long x=(long)Math.floor(b.minX/cellSize),y=(long)Math.floor(b.minY/cellSize),z=(long)Math.floor(b.minZ/cellSize);
            double nx=(double)(long)Math.floor(b.maxX/cellSize)-x+1,ny=(double)(long)Math.floor(b.maxY/cellSize)-y+1,nz=(double)(long)Math.floor(b.maxZ/cellSize)-z+1;
            if(nx<1||ny<1||nz<1||nx*ny*nz>MAX_CELLS)return null;
            List<Cell> result=new ArrayList<>((int)(nx*ny*nz));
            for(int i=0;i<(int)nx;i++)for(int j=0;j<(int)ny;j++)for(int k=0;k<(int)nz;k++)result.add(new Cell(dimension,x+i,y+j,z+k));
            return result;
        }
    }
}
