package advanced_teleport.detection;

import advanced_teleport.graph.GraphData.Road;
import advanced_teleport.graph.GraphData.RoadKind;
import advanced_teleport.graph.GraphData.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** A bounded, pure detector. UNLOADED samples must never be interpreted as air. */
public final class RoadDetector {
    public enum Kind { AIR, SOLID, BLUE_ICE, POWERED_RAIL, RAIL, OTHER_RAIL, PORTAL, UNLOADED }
    public enum Shape { NONE, NORTH_SOUTH, EAST_WEST, ASCENDING_NORTH, ASCENDING_SOUTH,
        ASCENDING_EAST, ASCENDING_WEST, SOUTH_EAST, SOUTH_WEST, NORTH_EAST, NORTH_WEST }
    public record Cell(int x, int y, int z) {
        public Cell add(int dx, int dy, int dz) { return new Cell(x + dx, y + dy, z + dz); }
    }
    public record BlockSample(Kind kind, Shape shape, boolean powered) {
        public static final BlockSample AIR = new BlockSample(Kind.AIR, Shape.NONE, false);
        public static final BlockSample SOLID = new BlockSample(Kind.SOLID, Shape.NONE, false);
        public static final BlockSample BLUE_ICE = new BlockSample(Kind.BLUE_ICE, Shape.NONE, false);
        public static final BlockSample UNLOADED = new BlockSample(Kind.UNLOADED, Shape.NONE, false);
    }
    @FunctionalInterface public interface BlockSource { BlockSample blockAt(int x, int y, int z); }
    public record ScanResult(List<Road> roads, Set<String> examinedIds, boolean complete) {
        public ScanResult {
            roads = List.copyOf(roads);
            examinedIds = Set.copyOf(examinedIds);
        }
    }

    public static ScanResult scan(BlockSource source, String dimension, Cell changed, DetectorSettings settings) {
        return new Scan(source, dimension, settings).around(changed);
    }

    /** Validates uploaded automatic segments against authoritative blocks without an area scan. */
    public static List<Road> validateCandidates(BlockSource source,String dimension,List<Road> candidates,DetectorSettings settings) {
        Set<Road> submitted=Set.copyOf(candidates);
        return validateExistingCandidates(source,dimension,candidates,settings).accepted().stream()
                .filter(submitted::contains).toList();
    }

    public record CandidateValidation(List<Road> accepted,Set<String> rejectedIds,Set<String> unknownIds) {
        public CandidateValidation {
            accepted=List.copyOf(accepted);
            rejectedIds=Set.copyOf(rejectedIds);
            unknownIds=Set.copyOf(unknownIds);
        }
    }

    /** For trusted existing entries, returns the current geometry even when road width has changed. */
    public static CandidateValidation validateExistingCandidates(BlockSource source,String dimension,List<Road> candidates,DetectorSettings settings) {
        Scan scan=new Scan(source,dimension,settings);
        Map<String,Road> accepted=new LinkedHashMap<>();
        Set<String> rejected=new LinkedHashSet<>(),unknown=new LinkedHashSet<>(),seen=new HashSet<>();
        for(Road candidate:candidates) {
            if(!seen.add(candidate.id()))continue;
            ParsedId parsed=parseId(dimension,candidate.id());
            if(candidate.protectedEntry() || !dimension.equals(candidate.dimension()) || parsed==null
                    || candidate.kind()!=(parsed.kind().equals("rail")?RoadKind.RAIL:RoadKind.ICE)) {
                rejected.add(candidate.id());continue;
            }
            try {
                Road actual=scan.evaluate(candidate.id(),parsed);
                if(actual==null)rejected.add(candidate.id());
                else accepted.put(actual.id(),actual);
            } catch(Unavailable unavailable) {unknown.add(candidate.id());}
        }
        return new CandidateValidation(new ArrayList<>(accepted.values()),rejected,unknown);
    }

    public static Optional<Cell> candidateAnchor(String dimension,String id) {
        ParsedId parsed=parseId(dimension,id);
        return parsed==null?Optional.empty():Optional.of(parsed.anchor());
    }

    private record ParsedId(String kind,Cell anchor,String suffix) {}

    private static ParsedId parseId(String dimension,String id) {
        if(dimension==null||id==null)return null;
        for(String kind:List.of("rail","ice","ice_corner")) {
            String prefix="auto:"+kind+":"+dimension+":";
            if(!id.startsWith(prefix))continue;
            String[] parts=id.substring(prefix.length()).split(":",-1);
            if(parts.length!=(kind.equals("rail")?5:4))return null;
            try {
                int x=Integer.parseInt(parts[0]),y=Integer.parseInt(parts[1]),z=Integer.parseInt(parts[2]);
                // Keep neighbor arithmetic inside integers and reject coordinates outside Minecraft's horizontal bounds.
                if(Math.abs((long)x)>30_000_000||Math.abs((long)z)>30_000_000||Math.abs((long)y)>1_000_000)return null;
                String suffix=parts[3];
                if(kind.equals("rail")) {
                    int direction=Integer.parseInt(parts[3]),rise=Integer.parseInt(parts[4]);
                    if((direction!=Direction.EAST.ordinal()&&direction!=Direction.SOUTH.ordinal())||rise < -1||rise>1)return null;
                    suffix=direction+":"+rise;
                } else if(kind.equals("ice")) {
                    if(!suffix.equals("x")&&!suffix.equals("z"))return null;
                } else {
                    int direction=Integer.parseInt(suffix);
                    if(direction<0||direction>=Direction.values().length)return null;
                    suffix=Integer.toString(direction);
                }
                if(!id.equals(prefix+x+":"+y+":"+z+":"+suffix))return null;
                return new ParsedId(kind,new Cell(x,y,z),suffix);
            } catch(NumberFormatException malformed) {return null;}
        }
        return null;
    }

    private enum Direction {
        NORTH(0,-1), EAST(1,0), SOUTH(0,1), WEST(-1,0);
        final int x, z;
        Direction(int x, int z) { this.x=x; this.z=z; }
        Direction opposite() { return values()[(ordinal()+2)%4]; }
        Cell offset(Cell cell, int distance) { return cell.add(x*distance,0,z*distance); }
    }

    private record Step(Direction direction, int rise) {}
    private record Visit(Cell cell, int distance) {}
    private record Span(Cell anchor, int width, boolean alongX) {
        Point point(String dimension) {
            return new Point(dimension,anchor.x()+(alongX?0.5:width/2.0),
                    anchor.y()+1.0,anchor.z()+(alongX?width/2.0:0.5));
        }
    }

    private static final class Unavailable extends RuntimeException {
        Unavailable() { super(null,null,false,false); }
    }

    private static final class Scan {
        private final BlockSource source;
        private final String dimension;
        private final DetectorSettings settings;
        private final Map<Cell, BlockSample> samples = new HashMap<>();
        private final Map<Cell, Boolean> qualifiedRails = new HashMap<>();
        private final Map<Cell, Boolean> qualifiedCorners = new HashMap<>();
        private final List<Road> roads = new ArrayList<>();
        private final Set<String> examinedIds = new LinkedHashSet<>();
        private boolean complete = true;

        Scan(BlockSource source, String dimension, DetectorSettings settings) {
            this.source=Objects.requireNonNull(source);
            this.dimension=Objects.requireNonNull(dimension);
            this.settings=Objects.requireNonNull(settings);
        }

        ScanResult around(Cell changed) {
            int radius=settings.scanRadius();
            // Rail slopes, the ice surface and the raised guardrail are all considered.
            int vertical=settings.verticalScanRadius();
            for (int y=changed.y()-vertical;y<=changed.y()+vertical;y++)
                for (int x=changed.x()-radius;x<=changed.x()+radius;x++)
                    for (int z=changed.z()-radius;z<=changed.z()+radius;z++) {
                        Cell cell=new Cell(x,y,z);
                        for (Direction direction:List.of(Direction.EAST,Direction.SOUTH)) {
                            for (int dy=-1;dy<=1;dy++) {
                                Cell other=direction.offset(cell,1).add(0,dy,0);
                                String id=id("rail",cell,direction.ordinal()+":"+dy);
                                candidate(id,()->railEdge(id,cell,other,direction));
                            }
                        }
                        candidate(id("ice",cell,"x"),()->iceEdge(id("ice",cell,"x"),cell,true));
                        candidate(id("ice",cell,"z"),()->iceEdge(id("ice",cell,"z"),cell,false));
                        for (Direction first:Direction.values()) {
                            Direction second=Direction.values()[(first.ordinal()+1)%4];
                            String id=id("ice_corner",cell,Integer.toString(first.ordinal()));
                            candidate(id,()->iceCorner(id,cell,first,second));
                        }
                    }
            return new ScanResult(roads,examinedIds,complete);
        }

        private String id(String kind, Cell cell, String suffix) {
            return "auto:"+kind+":"+dimension+":"+cell.x()+":"+cell.y()+":"+cell.z()+":"+suffix;
        }

        private Road evaluate(String id,ParsedId parsed) {
            Cell anchor=parsed.anchor();
            return switch(parsed.kind()) {
                case "rail" -> {
                    String[] suffix=parsed.suffix().split(":");
                    Direction direction=Direction.values()[Integer.parseInt(suffix[0])];
                    Cell other=direction.offset(anchor,1).add(0,Integer.parseInt(suffix[1]),0);
                    yield railEdge(id,anchor,other,direction);
                }
                case "ice" -> iceEdge(id,anchor,parsed.suffix().equals("x"));
                case "ice_corner" -> {
                    Direction direction=Direction.values()[Integer.parseInt(parsed.suffix())];
                    yield iceCorner(id,anchor,direction,Direction.values()[(direction.ordinal()+1)%4]);
                }
                default -> null;
            };
        }

        private void candidate(String id, Supplier<Road> operation) {
            try {
                Road road=operation.get();
                examinedIds.add(id);
                if (road!=null) roads.add(road);
            } catch (Unavailable ignored) {
                // This exact id cannot be safely replaced or deleted at a chunk boundary.
                complete=false;
            }
        }

        private BlockSample block(Cell cell) {
            BlockSample sample=samples.computeIfAbsent(cell,
                    p->Objects.requireNonNull(source.blockAt(p.x(),p.y(),p.z()),"block sample"));
            if (sample.kind()==Kind.UNLOADED) throw new Unavailable();
            return sample;
        }

        private boolean solid(Cell cell) {
            Kind kind=block(cell).kind();
            return kind==Kind.SOLID || kind==Kind.BLUE_ICE;
        }

        private boolean ice(Cell cell) { return block(cell).kind()==Kind.BLUE_ICE; }

        private boolean walkableIce(Cell cell) {
            return ice(cell) && !solid(cell.add(0,1,0)) && !solid(cell.add(0,2,0));
        }

        private boolean usableRail(BlockSample sample) {
            return sample.kind()==Kind.RAIL || sample.kind()==Kind.POWERED_RAIL && sample.powered();
        }

        private Road railEdge(String id, Cell first, Cell second, Direction direction) {
            BlockSample a=block(first);
            if (!usableRail(a)) return null;
            BlockSample b=block(second);
            if (!usableRail(b) || !connected(first,a,second,b,direction)
                    || !qualifiedRail(first) || !qualifiedRail(second)) return null;
            return new Road(id,dimension,List.of(railPoint(first),railPoint(second)),RoadKind.RAIL,false);
        }

        private Point railPoint(Cell cell) {
            return new Point(dimension,cell.x()+0.5,cell.y()+0.125,cell.z()+0.5);
        }

        private boolean connected(Cell a, BlockSample first, Cell b, BlockSample second, Direction direction) {
            for(Step s:steps(first.shape())) if(s.direction()==direction)
                for(Step t:steps(second.shape())) if(t.direction()==direction.opposite())
                    return a.y()+s.rise()==b.y()+t.rise();
            return false;
        }

        private boolean qualifiedRail(Cell cell) {
            Boolean existing=qualifiedRails.get(cell);
            if(existing!=null) return existing;
            boolean unknown=false;
            try {if(straightPair(cell)){qualifiedRails.put(cell,true);return true;}}
            catch(Unavailable ignored){unknown=true;}
            try {if(nearPairedCorner(cell)){qualifiedRails.put(cell,true);return true;}}
            catch(Unavailable ignored){unknown=true;}
            if(unknown)throw new Unavailable();
            qualifiedRails.put(cell,false);
            return false;
        }

        private boolean straightPair(Cell cell) {
            BlockSample state=block(cell);
            if(state.kind()!=Kind.POWERED_RAIL || !state.powered() || isCorner(state.shape())) return false;
            boolean eastWest=state.shape()==Shape.EAST_WEST || state.shape()==Shape.ASCENDING_EAST
                    || state.shape()==Shape.ASCENDING_WEST;
            int separation=settings.railStraightGap()+1;
            boolean unavailable=false;
            for(int sign:new int[]{-1,1}) {
                Cell candidate=cell.add(eastWest?0:sign*separation,0,eastWest?sign*separation:0);
                try {
                    BlockSample other=block(candidate);
                    if(other.kind()==Kind.POWERED_RAIL && other.powered() && state.shape()==other.shape()) return true;
                } catch(Unavailable ignored) { unavailable=true; }
            }
            if(unavailable) throw new Unavailable();
            return false;
        }

        private boolean pairedCorner(Cell cell) {
            Boolean known=qualifiedCorners.get(cell);
            if(known!=null)return known;
            BlockSample state=block(cell);
            if(state.kind()!=Kind.RAIL || !isCorner(state.shape())) return false;
            int diagonalSign=(state.shape()==Shape.NORTH_EAST || state.shape()==Shape.SOUTH_WEST)?-1:1;
            int maximum=settings.railCornerMaxGap()+1;
            boolean unknown=false;
            for(int dx=-maximum;dx<=maximum;dx++) for(int dz=-maximum;dz<=maximum;dz++) {
                if(dx==0 || dz==0 || Integer.signum(dx*dz)!=diagonalSign) continue;
                int gap=Math.abs(dx)+Math.abs(dz)-1;
                if(gap<settings.railCornerMinGap() || gap>settings.railCornerMaxGap()) continue;
                Cell partner=cell.add(dx,0,dz);
                try {
                    BlockSample other=block(partner);
                    if(other.kind()==Kind.RAIL && other.shape()==state.shape() && validCornerApproaches(cell,partner,state.shape())) {
                        qualifiedCorners.put(cell,true);qualifiedCorners.put(partner,true);return true;
                    }
                } catch(Unavailable ignored){unknown=true;}
            }
            if(unknown)throw new Unavailable();
            qualifiedCorners.put(cell,false);
            return false;
        }

        private boolean validCornerApproaches(Cell first,Cell second,Shape shape) {
            boolean unknown=false;
            Set<Cell> proof=new HashSet<>();
            for(Step arm:steps(shape)) {
                try {if(!pairedApproach(first,second,arm.direction(),proof))return false;}
                catch(Unavailable ignored){unknown=true;}
            }
            if(unknown)throw new Unavailable();
            // Only the proven continuous bend/transition is exceptional; nearby lone rails are not.
            for(Cell cell:proof)qualifiedRails.put(cell,true);
            return true;
        }

        private record Approach(Map<Cell,List<Cell>> paths,boolean unknown) {}

        private boolean pairedApproach(Cell first,Cell second,Direction direction,Set<Cell> proof) {
            boolean alongX=direction.x!=0;
            int separation=alongX?Math.abs(first.z()-second.z()):Math.abs(first.x()-second.x());
            if(separation!=settings.railStraightGap()+1)return false;
            int firstCoordinate=alongX?first.x():first.z(),secondCoordinate=alongX?second.x():second.z();
            int sign=alongX?direction.x:direction.z;
            int target=(sign>0?Math.max(firstCoordinate,secondCoordinate):Math.min(firstCoordinate,secondCoordinate))+sign;
            Approach a=approach(first,direction,Math.abs(target-firstCoordinate));
            Approach b=approach(second,direction,Math.abs(target-secondCoordinate));
            for(Cell left:a.paths().keySet())for(Cell right:b.paths().keySet()) {
                BlockSample l=block(left),r=block(right);
                if(left.y()==right.y()&&l.kind()==Kind.POWERED_RAIL&&l.powered()
                        &&r.kind()==Kind.POWERED_RAIL&&r.powered()&&l.shape()==r.shape()) {
                    proof.addAll(a.paths().get(left));proof.addAll(b.paths().get(right));return true;
                }
            }
            if(a.unknown()||b.unknown())throw new Unavailable();
            return false;
        }

        /** Follow a specific straight/sloping arm; no disconnected or alternate bend can prove a second lane. */
        private Approach approach(Cell start,Direction direction,int length) {
            Map<Cell,List<Cell>> frontier=Map.of(start,List.of(start));
            boolean unknown=false;
            for(int distance=0;distance<length;distance++) {
                Map<Cell,List<Cell>> next=new LinkedHashMap<>();
                for(var entry:frontier.entrySet()) {
                    Cell current=entry.getKey();
                    BlockSample state=block(current);
                    for(int dy:new int[]{0,-1,1}) {
                        Cell neighbor=direction.offset(current,1).add(0,dy,0);
                        try {
                            BlockSample other=block(neighbor);
                            if(usableRail(other)&&!isCorner(other.shape())&&connected(current,state,neighbor,other,direction)) {
                                List<Cell> route=new ArrayList<>(entry.getValue());route.add(neighbor);
                                next.putIfAbsent(neighbor,List.copyOf(route));
                            }
                        } catch(Unavailable ignored){unknown=true;}
                    }
                }
                frontier=next;
                if(frontier.isEmpty())break;
            }
            return new Approach(frontier,unknown);
        }

        private boolean nearPairedCorner(Cell start) {
            var queue=new ArrayDeque<Visit>();
            var visited=new HashSet<Cell>();
            queue.add(new Visit(start,0));
            int maximum=settings.railCornerMaxGap()+1;
            boolean unknown=false;
            while(!queue.isEmpty()) {
                Visit visit=queue.removeFirst();
                if(!visited.add(visit.cell())) continue;
                BlockSample state;
                try {state=block(visit.cell());}
                catch(Unavailable ignored){unknown=true;continue;}
                if(!usableRail(state)) continue;
                try {if(pairedCorner(visit.cell())&&Boolean.TRUE.equals(qualifiedRails.get(start))) return true;}
                catch(Unavailable ignored){unknown=true;}
                if(visit.distance()>=maximum) continue;
                for(Step step:steps(state.shape())) for(int dy=-1;dy<=1;dy++) {
                    Cell next=step.direction().offset(visit.cell(),1).add(0,dy,0);
                    try {
                        BlockSample other=block(next);
                        if(usableRail(other) && connected(visit.cell(),state,next,other,step.direction()))
                            queue.addLast(new Visit(next,visit.distance()+1));
                    } catch(Unavailable ignored){unknown=true;}
                }
            }
            if(unknown)throw new Unavailable();
            return false;
        }

        private Span span(Cell anchor, boolean alongX) {
            Direction cross=alongX?Direction.SOUTH:Direction.EAST;
            if(!ice(anchor) || ice(cross.offset(anchor,-1))) return null;
            int width=0;
            while(width<=settings.iceMaxWidth() && ice(cross.offset(anchor,width))) width++;
            if(width<settings.iceMinWidth() || width>settings.iceMaxWidth()) return null;
            if(!solid(cross.offset(anchor,-1).add(0,1,0))
                    || !solid(cross.offset(anchor,width).add(0,1,0))) return null;
            for(int offset=0;offset<width;offset++) if(!walkableIce(cross.offset(anchor,offset))) return null;
            return new Span(anchor,width,alongX);
        }

        private Road iceEdge(String id, Cell anchor, boolean alongX) {
            Span first=span(anchor,alongX);
            if(first==null) return null;
            Cell next=anchor.add(alongX?1:0,0,alongX?0:1);
            Span second=span(next,alongX);
            if(second==null) return null;
            return new Road(id,dimension,List.of(first.point(dimension),second.point(dimension)),RoadKind.ICE,false);
        }

        private Road iceCorner(String id, Cell anchor, Direction first, Direction second) {
            if(!ice(anchor)) return null;
            for(int width=settings.iceMinWidth();width<=settings.iceMaxWidth();width++) {
                Span a=arm(anchor,width,first);
                if(a==null || a.width()!=width) continue;
                Span b=arm(anchor,width,second);
                if(b==null || b.width()!=width) continue;
                // Keep the same adjacent-arm segments for bends, T-junctions and crossroads.
                // Every other side must be closed by a complete wall or continue as a
                // fully guarded, equally wide arm; a missing wall is not itself a road.
                if(!guardedSideOrArm(anchor,width,first.opposite())
                        || !guardedSideOrArm(anchor,width,second.opposite())) continue;
                boolean floor=true;
                for(int dx=0;dx<width && floor;dx++) for(int dz=0;dz<width;dz++)
                    if(!walkableIce(anchor.add(dx,0,dz))) { floor=false;break; }
                if(floor) return new Road(id,dimension,List.of(a.point(dimension),
                        new Point(dimension,anchor.x()+width/2.0,anchor.y()+1,anchor.z()+width/2.0),
                        b.point(dimension)),RoadKind.ICE,false);
            }
            return null;
        }

        private Span arm(Cell base, int width, Direction direction) {
            return switch(direction) {
                case NORTH->span(base.add(0,0,-1),false);
                case SOUTH->span(base.add(0,0,width),false);
                case WEST->span(base.add(-1,0,0),true);
                case EAST->span(base.add(width,0,0),true);
            };
        }

        private boolean guardedSideOrArm(Cell base,int width,Direction direction) {
            if(guardedSide(base,width,direction))return true;
            Span continuation=arm(base,width,direction);
            return continuation!=null&&continuation.width()==width;
        }

        private boolean guardedSide(Cell base, int width, Direction direction) {
            for(int i=0;i<width;i++) {
                Cell side=switch(direction) {
                    case NORTH->base.add(i,1,-1);
                    case SOUTH->base.add(i,1,width);
                    case WEST->base.add(-1,1,i);
                    case EAST->base.add(width,1,i);
                };
                if(!solid(side)) return false;
            }
            return true;
        }
    }

    private static boolean isCorner(Shape shape) {
        return shape==Shape.NORTH_EAST || shape==Shape.NORTH_WEST
                || shape==Shape.SOUTH_EAST || shape==Shape.SOUTH_WEST;
    }

    private static List<Step> steps(Shape shape) {
        return switch(shape) {
            case NORTH_SOUTH->List.of(new Step(Direction.NORTH,0),new Step(Direction.SOUTH,0));
            case EAST_WEST->List.of(new Step(Direction.EAST,0),new Step(Direction.WEST,0));
            case ASCENDING_NORTH->List.of(new Step(Direction.NORTH,1),new Step(Direction.SOUTH,0));
            case ASCENDING_SOUTH->List.of(new Step(Direction.NORTH,0),new Step(Direction.SOUTH,1));
            case ASCENDING_EAST->List.of(new Step(Direction.EAST,1),new Step(Direction.WEST,0));
            case ASCENDING_WEST->List.of(new Step(Direction.EAST,0),new Step(Direction.WEST,1));
            case NORTH_EAST->List.of(new Step(Direction.NORTH,0),new Step(Direction.EAST,0));
            case NORTH_WEST->List.of(new Step(Direction.NORTH,0),new Step(Direction.WEST,0));
            case SOUTH_EAST->List.of(new Step(Direction.SOUTH,0),new Step(Direction.EAST,0));
            case SOUTH_WEST->List.of(new Step(Direction.SOUTH,0),new Step(Direction.WEST,0));
            default->List.of();
        };
    }
}
