package advanced_teleport.runtime;

import advanced_teleport.config.AtpConfig;
import advanced_teleport.graph.Geometry;
import advanced_teleport.graph.GraphData.*;
import advanced_teleport.graph.GraphEditor;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** Server-thread portal observation, pairing and bounded revalidation. */
public final class PortalTracker {
    private static final int MAX_SIDE=21;
    private static final int[] RETRY_DELAYS={2,5,20,40};
    private final MinecraftServer server;
    private final GraphEditor editor;
    private final Supplier<AtpConfig> config;
    private final Set<String> linkChecks=new LinkedHashSet<>();
    private final Map<String,Lookup> entrances=new LinkedHashMap<>();
    private final Map<String,Surface> observed=new HashMap<>();
    private final Map<String,Long> observedAt=new HashMap<>();
    private long clock;

    private record Surface(Point anchor,Point extent,boolean endPortal) {}
    private record Lookup(Point point,int attempt,long readyAt) {}

    public PortalTracker(MinecraftServer server,GraphEditor editor,Supplier<AtpConfig> config) {
        this.server=Objects.requireNonNull(server);
        this.editor=Objects.requireNonNull(editor);
        this.config=Objects.requireNonNull(config);
        scheduleLinkChecks();
    }

    /** Handles actual edits. Nearby discovery uses the configured distance and loaded POIs. */
    public void changed(ServerWorld world,BlockPos pos) {
        Point changed=point(world,pos);
        observed.forEach((key,surface)->{
            if(near(changed,surface.anchor(),surface.extent(),1))observedAt.remove(key);
        });
        if(world.isChunkLoaded(pos)&&isPortal(world.getBlockState(pos)))loadedPortal(world,pos);
        for(PortalLink link:editor.snapshot().portals()) {
            if(link.protectedEntry())continue;
            if(near(changed,link.first(),link.firstExtent(),1)
                    ||!link.id().startsWith("auto:arrival:")&&near(changed,link.second(),link.secondExtent(),1))
                linkChecks.add(link.id());
        }
        int radius=(int)Math.ceil(config.get().portalConnectionDistance)+1;
        // POI lookup avoids (2r+1)^3 block reads for a large configured connection radius.
        // Only already loaded portal blocks are examined here; actual portal exit resolution
        // is the separate vanilla lookup below and may load its destination area.
        try(var pois=world.getPointOfInterestStorage().getInCircle(type->type.matchesKey(PointOfInterestTypes.NETHER_PORTAL),
                pos,radius,PointOfInterestStorage.OccupationStatus.ANY)) {
            pois.map(poi->poi.getPos().toImmutable()).filter(world::isChunkLoaded).forEach(portal->loadedPortal(world,portal));
        }
    }

    /** Chunk scans pass each real portal block, never a rounded road-scan grid center. */
    public void loadedPortal(ServerWorld world,BlockPos pos) {
        if(!world.isChunkLoaded(pos)||!isPortal(world.getBlockState(pos)))return;
        String dimension=world.getRegistryKey().getValue().toString();
        Point cell=point(world,pos);
        for(Surface known:observed.values())if(!known.endPortal()&&known.anchor().dimension().equals(dimension)
                &&observedAt.getOrDefault(key(known.anchor()),-1L)==clock
                &&within(cell.x(),known.anchor().x(),known.extent().x())
                &&within(cell.y(),known.anchor().y(),known.extent().y())
                &&within(cell.z(),known.anchor().z(),known.extent().z())
                &&validKnownAnchor(world,known)) {
            // A chunk supplies every portal block; reuse an already discovered surface.
            // The queued lookup refreshes its actual dimensions before updating the graph.
            enqueue(known.anchor());
            return;
        }
        Surface surface=surfaceAt(world,pos,false);
        if(surface==null) {
            enqueue(point(world,pos));
            return;
        }
        Surface previous=observed.put(key(surface.anchor()),surface);
        observedAt.put(key(surface.anchor()),clock);
        if(!surface.equals(previous))scheduleLinkChecks();
        if(!surface.endPortal())enqueue(surface.anchor());
    }

    /** At most one entrance or existing-link check is performed per server tick. */
    public void tick() {
        clock++;
        Lookup ready=entrances.values().stream().filter(job->job.readyAt()<=clock).findFirst().orElse(null);
        if(ready!=null&&(linkChecks.isEmpty()||(clock&1)==0))checkEntrance(ready.point());
        else if(!linkChecks.isEmpty())checkLink(linkChecks.iterator().next());
        else if(ready!=null)checkEntrance(ready.point());
    }

    public Set<String> pendingLinkIds(){return Set.copyOf(linkChecks);}
    public List<Point> pendingEntrances(){return entrances.values().stream().map(Lookup::point).toList();}

    /** Can be called synchronously for a pending endpoint relevant to a requested journey. */
    public boolean checkEntrance(Point entrance) {
        Lookup job=entrances.get(key(entrance));
        if(job!=null&&job.readyAt()>clock)return false;
        entrances.remove(key(entrance));
        int attempt=job==null?0:job.attempt();
        Surface source=surfaceAt(entrance,true);
        if(source==null) {
            observed.remove(key(entrance));
            observedAt.remove(key(entrance));
            return true;
        }
        observed.put(key(source.anchor()),source);
        observedAt.put(key(source.anchor()),clock);
        if(source.endPortal())return true;
        Surface exit=findExit(source);
        if(exit!=null)recordPair(source,exit);
        else if(attempt<RETRY_DELAYS.length) {
            // Activation and POI indexing need not finish in the same tick. Missing exits
            // receive bounded delayed retries rather than an indefinite negative cache.
            entrances.putIfAbsent(key(source.anchor()),new Lookup(source.anchor(),attempt+1,clock+RETRY_DELAYS[attempt]));
        }
        return true;
    }

    /** Rechecks both directions so genuine one-way vanilla connections remain usable. */
    public void checkLink(String id) {
        linkChecks.remove(id);
        PortalLink link=editor.snapshot().portals().stream().filter(value->value.id().equals(id)).findFirst().orElse(null);
        if(link==null||link.protectedEntry())return;
        Surface first=surfaceAt(link.first(),true);
        if(link.id().startsWith("auto:arrival:")) {
            if(first==null)editor.removeAutoPortal(id);
            else put(new PortalLink(id,first.anchor(),link.second(),false,first.extent(),link.secondExtent()));
            return;
        }
        Surface second=surfaceAt(link.second(),true);
        if(first==null||second==null) {
            if(editor.removeAutoPortal(id))scheduleLinkChecks();
            if(first!=null)enqueue(first.anchor());
            if(second!=null)enqueue(second.anchor());
            return;
        }
        if(!vanillaPair(first.anchor().dimension(),second.anchor().dimension())) {
            // Custom-dimension pairings are learned from actual travel, not guessed.
            put(new PortalLink(id,first.anchor(),second.anchor(),false,first.extent(),second.extent()));
            return;
        }
        Surface fromFirst=findExit(first),fromSecond=findExit(second);
        boolean stillPaired=samePortal(fromFirst,second)||samePortal(fromSecond,first);
        if(!stillPaired)editor.removeAutoPortal(id);
        else {
            String actualId=pairId(first,second);
            if(!actualId.equals(id))editor.removeAutoPortal(id);
            recordPair(first,second);
        }
        if(fromFirst!=null)recordPair(first,fromFirst);else enqueue(first.anchor());
        if(fromSecond!=null)recordPair(second,fromSecond);else enqueue(second.anchor());
    }

    public void recordTravel(Point entrance,Point landing,Point exit) {
        Surface first=surfaceAt(entrance,true);
        if(first==null)return;
        if(first.endPortal()) {
            String id="auto:arrival:"+UUID.nameUUIDFromBytes((key(first.anchor())+"|"+key(landing)).getBytes(StandardCharsets.UTF_8));
            put(new PortalLink(id,first.anchor(),landing,false,first.extent(),landing));
            return;
        }
        Surface second=exit==null?null:surfaceAt(exit,true);
        if(second!=null)recordPair(first,second);
    }

    private void enqueue(Point point) {
        entrances.putIfAbsent(key(point),new Lookup(point,0,clock+1));
    }

    private void scheduleLinkChecks() {
        editor.snapshot().portals().stream().filter(portal->!portal.protectedEntry())
                .map(PortalLink::id).filter(id->id.startsWith("auto:")).forEach(linkChecks::add);
    }

    private void recordPair(Surface first,Surface second) {
        if(key(first.anchor()).compareTo(key(second.anchor()))>0){Surface swap=first;first=second;second=swap;}
        String id=pairId(first,second);
        if(put(new PortalLink(id,first.anchor(),second.anchor(),false,first.extent(),second.extent())))scheduleLinkChecks();
    }

    private boolean put(PortalLink link) {
        Snapshot graph=editor.snapshot();
        long count=(long)graph.cities().size()+graph.roads().size()+graph.stations().size()+graph.portals().size();
        if(count>=config.get().maxGraphEntries&&graph.portals().stream().noneMatch(old->old.id().equals(link.id())))return false;
        return editor.putAutoPortal(link);
    }

    private static String pairId(Surface first,Surface second) {
        String a=key(first.anchor()),b=key(second.anchor());
        if(a.compareTo(b)>0){String swap=a;a=b;b=swap;}
        return "auto:portal:"+UUID.nameUUIDFromBytes((a+"|"+b).getBytes(StandardCharsets.UTF_8));
    }

    private Surface findExit(Surface source) {
        ServerWorld from=world(source.anchor().dimension());
        if(from==null||source.endPortal())return null;
        RegistryKey<World> key=from.getRegistryKey();
        if(!key.equals(World.OVERWORLD)&&!key.equals(World.NETHER))return null;
        ServerWorld destination=server.getWorld(key.equals(World.NETHER)?World.OVERWORLD:World.NETHER);
        if(destination==null)return null;
        double scale=DimensionType.getCoordinateScaleFactor(from.getDimension(),destination.getDimension());
        BlockPos block=block(source.anchor());
        // Use the canonical entrance block center for vanilla coordinate scaling.
        BlockPos target=destination.getWorldBorder().clamp((block.getX()+.5)*scale,block.getY(),(block.getZ()+.5)*scale);
        return destination.getPortalForcer().getPortalRect(target,destination.getRegistryKey().equals(World.NETHER),destination.getWorldBorder())
                .map(rectangle->surfaceAt(destination,rectangle.lowerLeft,true)).orElse(null);
    }

    private Surface surfaceAt(Point point,boolean loadChunks) {
        ServerWorld world=world(point.dimension());
        return world==null?null:surfaceAt(world,block(point),loadChunks);
    }

    private Surface surfaceAt(ServerWorld world,BlockPos candidate,boolean loadChunks) {
        BlockState state=state(world,candidate,loadChunks);
        if(state==null||!isPortal(state))return null;
        if(state.isOf(Blocks.END_PORTAL))return endSurface(world,candidate,loadChunks);
        Direction.Axis axis=state.get(NetherPortalBlock.AXIS);
        Direction negative=axis==Direction.Axis.X?Direction.WEST:Direction.NORTH;
        Direction positive=negative.getOpposite();
        BlockPos origin=candidate;
        for(int i=0;i<MAX_SIDE;i++) {
            BlockState below=state(world,origin.down(),loadChunks);
            if(below==null)return null;
            if(!sameNether(below,axis))break;
            origin=origin.down();
        }
        for(int i=0;i<MAX_SIDE;i++) {
            BlockState previous=state(world,origin.offset(negative),loadChunks);
            if(previous==null)return null;
            if(!sameNether(previous,axis))break;
            origin=origin.offset(negative);
        }
        int width=0,height=0;
        while(width<=MAX_SIDE) {
            BlockState current=state(world,origin.offset(positive,width),loadChunks);
            if(current==null)return null;
            if(!sameNether(current,axis))break;
            width++;
        }
        while(height<=MAX_SIDE) {
            BlockState current=state(world,origin.up(height),loadChunks);
            if(current==null)return null;
            if(!sameNether(current,axis))break;
            height++;
        }
        if(width<1||width>MAX_SIDE||height<1||height>MAX_SIDE)return null;
        for(int x=0;x<width;x++)for(int y=0;y<height;y++) {
            BlockState current=state(world,origin.offset(positive,x).up(y),loadChunks);
            if(current==null||!sameNether(current,axis))return null;
        }
        String dimension=world.getRegistryKey().getValue().toString();
        Point anchor=axis==Direction.Axis.X?new Point(dimension,origin.getX(),origin.getY(),origin.getZ()+.5)
                :new Point(dimension,origin.getX()+.5,origin.getY(),origin.getZ());
        Point extent=new Point(dimension,anchor.x()+(axis==Direction.Axis.X?width:0),anchor.y()+height,
                anchor.z()+(axis==Direction.Axis.Z?width:0));
        return new Surface(anchor,extent,false);
    }

    private Surface endSurface(ServerWorld world,BlockPos candidate,boolean loadChunks) {
        BlockPos origin=candidate;
        for(Direction direction:List.of(Direction.WEST,Direction.NORTH))for(int i=0;i<MAX_SIDE;i++) {
            BlockState previous=state(world,origin.offset(direction),loadChunks);
            if(previous==null)return null;
            if(!previous.isOf(Blocks.END_PORTAL))break;
            origin=origin.offset(direction);
        }
        int width=0,depth=0;
        while(width<=MAX_SIDE) {
            BlockState current=state(world,origin.east(width),loadChunks);
            if(current==null)return null;
            if(!current.isOf(Blocks.END_PORTAL))break;
            width++;
        }
        while(depth<=MAX_SIDE) {
            BlockState current=state(world,origin.south(depth),loadChunks);
            if(current==null)return null;
            if(!current.isOf(Blocks.END_PORTAL))break;
            depth++;
        }
        if(width<1||width>MAX_SIDE||depth<1||depth>MAX_SIDE)return null;
        for(int x=0;x<width;x++)for(int z=0;z<depth;z++) {
            BlockState current=state(world,origin.add(x,0,z),loadChunks);
            if(current==null)return null;
            if(!current.isOf(Blocks.END_PORTAL))return new Surface(point(world,candidate),point(world,candidate),true);
        }
        String dimension=world.getRegistryKey().getValue().toString();
        Point anchor=new Point(dimension,origin.getX(),origin.getY()+.75,origin.getZ());
        return new Surface(anchor,new Point(dimension,origin.getX()+width,anchor.y(),origin.getZ()+depth),true);
    }

    private static BlockState state(ServerWorld world,BlockPos pos,boolean loadChunks) {
        if(world.isOutOfHeightLimit(pos))return Blocks.AIR.getDefaultState();
        if(!world.isChunkLoaded(pos)) {
            if(!loadChunks)return null;
            world.getChunk(pos);
        }
        return world.getBlockState(pos);
    }

    /** Player-travel observation only. Connection distance is handled by full-surface geometry. */
    public static Point findPortal(ServerWorld world,BlockPos center) {
        BlockPos nearest=null;double best=Double.POSITIVE_INFINITY;
        for(BlockPos pos:BlockPos.iterate(center.add(-3,-3,-3),center.add(3,3,3))) {
            if(!world.isChunkLoaded(pos)||!isPortal(world.getBlockState(pos)))continue;
            double distance=pos.getSquaredDistance(center);
            if(distance<best){nearest=pos.toImmutable();best=distance;}
        }
        return nearest==null?null:point(world,nearest);
    }

    private ServerWorld world(String dimension){return server.getWorld(RegistryKey.of(RegistryKeys.WORLD,new Identifier(dimension)));}
    private static boolean sameNether(BlockState state,Direction.Axis axis){return state.isOf(Blocks.NETHER_PORTAL)&&state.get(NetherPortalBlock.AXIS)==axis;}
    private static boolean isPortal(BlockState state){return state.isOf(Blocks.NETHER_PORTAL)||state.isOf(Blocks.END_PORTAL);}
    private static Point point(ServerWorld world,BlockPos pos){return new Point(world.getRegistryKey().getValue().toString(),pos.getX()+.5,pos.getY(),pos.getZ()+.5);}
    private static BlockPos block(Point point){return BlockPos.ofFloored(point.x(),point.y(),point.z());}
    private static String key(Point point){BlockPos pos=block(point);return point.dimension()+":"+pos.getX()+":"+pos.getY()+":"+pos.getZ();}
    private static boolean samePortal(Surface a,Surface b){return a!=null&&b!=null&&key(a.anchor()).equals(key(b.anchor()));}
    private static boolean near(Point point,Point anchor,Point extent,double distance) {
        return point.dimension().equals(anchor.dimension())
                &&Geometry.distanceSquared(point,Geometry.closestOnRectangle(point,anchor,extent))<=distance*distance;
    }
    private static boolean within(double value,double first,double second) {
        return first==second?Math.abs(value-first)<1e-6:value>=Math.min(first,second)&&value<Math.max(first,second);
    }
    private static boolean validKnownAnchor(ServerWorld world,Surface surface) {
        BlockState state=state(world,block(surface.anchor()),false);
        Direction.Axis axis=surface.anchor().x()!=surface.extent().x()?Direction.Axis.X:Direction.Axis.Z;
        return state!=null&&sameNether(state,axis);
    }
    private static boolean vanillaPair(String first,String second) {
        return !first.equals(second)&&Set.of("minecraft:overworld","minecraft:the_nether").contains(first)
                &&Set.of("minecraft:overworld","minecraft:the_nether").contains(second);
    }
}
