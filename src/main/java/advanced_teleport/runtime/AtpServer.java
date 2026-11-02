package advanced_teleport.runtime;

import advanced_teleport.config.AtpConfig;
import advanced_teleport.detection.*;
import advanced_teleport.graph.*;
import advanced_teleport.graph.GraphData.*;
import advanced_teleport.modid.Advanced_teleport;
import advanced_teleport.network.Protocol;
import advanced_teleport.network.SnapshotAssembly;
import advanced_teleport.storage.*;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** All authoritative state and mutations live on the server thread. */
public final class AtpServer implements AutoCloseable {
    private static final Map<MinecraftServer, AtpServer> SERVERS = new IdentityHashMap<>();
    private final MinecraftServer server;
    private final String worldName;
    private final Path configFile;
    private final GraphStore store;
    private final GraphEditor editor;
    private AtpConfig config;
    private GraphEngine engine;
    private long engineRevision = -1, persistedRevision, configEpoch, clock, nextRequest, nextTransfer;
    private boolean storageHealthy = true;
    private final Set<Change> changes = new LinkedHashSet<>();
    private final Map<String, ChunkJob> chunkJobs = new LinkedHashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Transfer> outgoing = new HashMap<>();
    private final Map<UUID, Stamp> delivered = new HashMap<>();
    private final Map<UUID, Long> lastClientChange = new HashMap<>();
    private final Map<UUID, Long> lastRequest = new HashMap<>();
    private final Map<String,Long> portalLookups = new HashMap<>();
    private final Set<String> portalChecks = new LinkedHashSet<>();
    private record Change(String dimension, BlockPos pos) {}
    private record Pending(long id, long revision, long deadline, Point source, Point target, Point expected) {}
    private record Stamp(long revision, long configEpoch) {}
    private static final class ChunkJob {
        final ServerWorld world; final net.minecraft.world.chunk.WorldChunk chunk;
        final Set<BlockPos> seeds = new LinkedHashSet<>(); int cursor;
        ChunkJob(ServerWorld world,net.minecraft.world.chunk.WorldChunk chunk){this.world=world;this.chunk=chunk;}
    }
    private static final class Transfer {
        final long id; final byte[] data; final Stamp stamp; int offset;
        Transfer(long id, byte[] data, Stamp stamp) { this.id = id; this.data = data; this.stamp=stamp; }
    }

    public AtpServer(MinecraftServer server, Path root) throws Exception {
        this.server = server;
        // Display names may repeat (e.g. two "New World" saves); the save folder is unique.
        worldName=server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).toAbsolutePath().normalize().getFileName().toString();
        configFile = root.resolve("config.json");
        config = AtpConfig.load(configFile);
        String bindAddress=Objects.toString(server.getServerIp(),"");
        String endpoint = server.isDedicated() ? (bindAddress.isBlank() ? "0.0.0.0" : bindAddress)
                + ":" + server.getServerPort() : "singleplayer";
        Path directory = DataPaths.worldDirectory(root, endpoint, worldName);
        store = new GraphStore(directory.resolve("graph.sqlite"));
        Snapshot loaded = store.load();
        if(entryCount(loaded)>config.maxGraphEntries)throw new IllegalStateException("Saved graph exceeds maxGraphEntries; increase it in "+configFile);
        editor = new GraphEditor(loaded, config.graphSettings());
        schedulePortalChecks();
        persistedRevision = loaded.revision();
        SERVERS.put(server, this);
        Advanced_teleport.LOGGER.info("ATP world data: {} (revision {})", directory, loaded.revision());
    }

    public static AtpServer get(MinecraftServer server) { return SERVERS.get(server); }
    public String worldName(){return worldName;}
    public Snapshot snapshot() { return editor.snapshot(); }
    public AtpConfig config() { return Protocol.JSON.fromJson(Protocol.JSON.toJson(config), AtpConfig.class); }
    public boolean enabled() { return !config.mode.equals("off"); }
    public boolean restricts(ServerCommandSource source) { return enabled() && source.isExecutedByPlayer() && !source.hasPermissionLevel(2); }
    public GraphEngine engine() {
        if (engine == null || engineRevision != snapshot().revision()) {
            engine = new GraphEngine(snapshot(), config.graphSettings()); engineRevision = snapshot().revision();
        }
        return engine;
    }
    public void requireThread() {
        if (!server.isOnThread()) throw new IllegalStateException("ATP API must be called on the Minecraft server thread");
    }
    public GraphEditor editor() { requireThread(); return editor; }
    public void checkRegistration(String type,String id) {
        requireThread();Snapshot s=snapshot();
        boolean exists=switch(type){
            case "city"->s.cities().stream().anyMatch(e->e.id().equals(id));
            case "road"->s.roads().stream().anyMatch(e->e.id().equals(id));
            case "station"->s.stations().stream().anyMatch(e->e.id().equals(id));
            case "portal"->s.portals().stream().anyMatch(e->e.id().equals(id));
            default->false;
        };
        if(!exists && entryCount(s)>=config.maxGraphEntries)throw new IllegalStateException("ATP graph has reached maxGraphEntries");
    }
    private static long entryCount(Snapshot s){return (long)s.cities().size()+s.roads().size()+s.stations().size()+s.portals().size();}
    public void reload() throws Exception {
        requireThread();var replacement=AtpConfig.load(configFile);
        if(entryCount(snapshot())>replacement.maxGraphEntries)throw new IllegalArgumentException("maxGraphEntries is smaller than the current graph");
        config = replacement; engine = null;
        pending.clear(); configEpoch++;
        for (var player : server.getPlayerManager().getPlayerList()) server.getCommandManager().sendCommandTree(player);
    }
    public void setMode(String mode) throws Exception {
        AtpConfig replacement = config(); replacement.mode = mode; replacement.save(configFile); reload();
    }
    public static Point point(Entity entity) {
        return new Point(entity.getWorld().getRegistryKey().getValue().toString(), entity.getX(), entity.getY(), entity.getZ());
    }
    private ServerWorld world(String dimension) {
        return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, new Identifier(dimension)));
    }
    public void enqueue(String dimension, BlockPos pos) {
        if(changes.size()<8192)changes.add(new Change(dimension, pos.toImmutable()));
        else {
            ServerWorld world=world(dimension);
            if(world!=null&&world.isChunkLoaded(pos))chunkLoaded(world,world.getChunk(pos.getX()>>4,pos.getZ()>>4));
        }
    }
    public void chunkLoaded(ServerWorld world,net.minecraft.world.chunk.WorldChunk chunk) {
        String dimension=world.getRegistryKey().getValue().toString();
        String key=dimension+":"+chunk.getPos().toLong();
        if(chunkJobs.containsKey(key))return;
        ChunkJob job=new ChunkJob(world,chunk);
        // Old segments also seed a scan: a road removed while this chunk was unloaded must disappear.
        for(Road road:snapshot().roads())if(!road.protectedEntry()&&road.dimension().equals(dimension)) {
            for(Point p:road.points()) {
                BlockPos pos=BlockPos.ofFloored(p.x(),p.y()-1,p.z());
                int margin=new DetectorSettings(config.railStraightGap,config.railCornerMinGap,config.railCornerMaxGap,config.iceMinWidth,config.iceMaxWidth).scanRadius();
                if(pos.getX()>=chunk.getPos().getStartX()-margin&&pos.getX()<=chunk.getPos().getEndX()+margin
                        &&pos.getZ()>=chunk.getPos().getStartZ()-margin&&pos.getZ()<=chunk.getPos().getEndZ()+margin)job.seeds.add(seed(pos));
            }
        }
        chunkJobs.put(key,job);
    }
    private static BlockPos seed(BlockPos p){return new BlockPos(Math.floorDiv(p.getX(),8)*8+4,Math.floorDiv(p.getY(),4)*4+2,Math.floorDiv(p.getZ(),8)*8+4);}
    private void discoverLoadedRoads() {
        if(chunkJobs.isEmpty()||changes.size()>4096)return;
        var entry=chunkJobs.entrySet().iterator().next();var job=entry.getValue();
        if(!job.world.isChunkLoaded(job.chunk.getPos().x,job.chunk.getPos().z)){chunkJobs.remove(entry.getKey());return;}
        var sections=job.chunk.getSectionArray();int budget=4096;
        while(budget-->0 && job.cursor<sections.length*4096) {
            int cursor=job.cursor++;var section=sections[cursor>>12];
            if(section.isEmpty()){job.cursor=((cursor>>12)+1)*4096;continue;}
            int x=cursor&15,z=(cursor>>4)&15,y=(cursor>>8)&15;
            var state=section.getBlockState(x,y,z);
            if(state.getBlock() instanceof net.minecraft.block.AbstractRailBlock||state.isOf(Blocks.BLUE_ICE)||state.isOf(Blocks.NETHER_PORTAL)) {
                job.seeds.add(seed(new BlockPos(job.chunk.getPos().getStartX()+x,job.world.getBottomY()+(cursor>>12)*16+y,job.chunk.getPos().getStartZ()+z)));
            }
        }
        if(job.cursor>=sections.length*4096) {
            chunkJobs.remove(entry.getKey());
            String dimension=job.world.getRegistryKey().getValue().toString();
            for(BlockPos pos:job.seeds)enqueue(dimension,pos);
        }
    }

    public void clientChange(ServerPlayerEntity player, String dimension, BlockPos pos) {
        // Updates are hints; the submitted geometry never becomes authoritative.
        if (!point(player).dimension().equals(dimension) || player.squaredDistanceTo(pos.getX(),pos.getY(),pos.getZ()) > 128 * 128) return;
        long last = lastClientChange.getOrDefault(player.getUuid(), -1L);
        if (last == clock) return;
        lastClientChange.put(player.getUuid(), clock);
        if (player.getServerWorld().isChunkLoaded(pos)) enqueue(dimension,pos);
    }

    public void tick() {
        clock++;
        discoverLoadedRoads();
        int work = config.maxPendingChangesPerTick;
        while (work-- > 0 && !changes.isEmpty()) {
            Change change = changes.iterator().next(); changes.remove(change);
            ServerWorld world = world(change.dimension());
            if (world == null || !world.isChunkLoaded(change.pos())) continue;
            var settings = new DetectorSettings(config.railStraightGap,config.railCornerMinGap,config.railCornerMaxGap,config.iceMinWidth,config.iceMaxWidth);
            var p = change.pos();
            var scan = RoadDetector.scan(new MinecraftBlockSource(world), change.dimension(), new RoadDetector.Cell(p.getX(),p.getY(),p.getZ()),settings);
            long retained=snapshot().roads().stream().filter(r->r.protectedEntry()||!scan.examinedIds().contains(r.id())).count();
            int room=(int)Math.max(0,config.maxGraphEntries-retained-snapshot().cities().size()-snapshot().stations().size()-snapshot().portals().size());
            editor.applyAutomatic(scan.roads().stream().limit(room).toList(),scan.examinedIds());
            invalidatePortals(change);
            Point nearbyPortal=findPortal(world,p);
            if(nearbyPortal!=null)linkExistingPortal(nearbyPortal);
        }
        recheckOnePortal();
        pending.entrySet().removeIf(entry -> {
            if (entry.getValue().deadline() >= clock) return false;
            var player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player != null) message(player,"传送计算已超时，请等待数据同步后重试。");
            return true;
        });
        if (clock % 20 == 0 && persistedRevision != snapshot().revision()) persist();
        if (clock % 10 == 0) {
            byte[] data = null;
            Stamp current=new Stamp(snapshot().revision(),configEpoch);
            for (var player : server.getPlayerManager().getPlayerList()) {
                // Finish an older transfer before beginning a newer snapshot.
                if (!outgoing.containsKey(player.getUuid()) && !current.equals(delivered.get(player.getUuid()))
                        && ServerPlayNetworking.canSend(player,Protocol.SNAPSHOT)) {
                    if(data==null)data=syncBytes();
                    queueSync(player,data);
                }
            }
        }
        pumpTransfers();
    }
    private void persist() {
        try { store.save(snapshot()); persistedRevision = snapshot().revision(); storageHealthy = true; }
        catch (Exception failure) { storageHealthy = false; Advanced_teleport.LOGGER.error("ATP could not persist graph; restricted TP disabled until storage recovers",failure); }
    }
    private byte[] syncBytes() {
        byte[] data = Protocol.JSON.toJson(new Protocol.Sync(worldName,config,snapshot())).getBytes(StandardCharsets.UTF_8);
        if (data.length > SnapshotAssembly.MAX_BYTES) {
            Advanced_teleport.LOGGER.error("ATP graph exceeds 32 MiB snapshot limit; graph synchronization suspended");
            return null;
        }
        return data;
    }
    public void sync(ServerPlayerEntity player) { queueSync(player,syncBytes()); }
    private void queueSync(ServerPlayerEntity player, byte[] data) {
        if(data==null){message(player,"路网超过同步大小上限，请联系管理员。");return;}
        if (ServerPlayNetworking.canSend(player,Protocol.SNAPSHOT)) outgoing.put(player.getUuid(),new Transfer(++nextTransfer,data,new Stamp(snapshot().revision(),configEpoch)));
    }
    private void pumpTransfers() {
        var iterator = outgoing.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next(); var player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) { iterator.remove(); continue; }
            Transfer transfer = entry.getValue();
            int count = (transfer.data.length + SnapshotAssembly.CHUNK_BYTES-1)/SnapshotAssembly.CHUNK_BYTES;
            for (int i=0;i<4 && transfer.offset<transfer.data.length;i++) {
                int end = Math.min(transfer.offset+SnapshotAssembly.CHUNK_BYTES,transfer.data.length);
                var buf = PacketByteBufs.create();
                buf.writeLong(transfer.id); buf.writeVarInt(transfer.offset/SnapshotAssembly.CHUNK_BYTES); buf.writeVarInt(count);
                buf.writeByteArray(Arrays.copyOfRange(transfer.data,transfer.offset,end));
                ServerPlayNetworking.send(player,Protocol.SNAPSHOT,buf); transfer.offset=end;
            }
            if (transfer.offset == transfer.data.length) { iterator.remove(); delivered.put(player.getUuid(),transfer.stamp); }
        }
    }

    public void request(ServerCommandSource source, Collection<? extends Entity> targets, Point target) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        if (targets.size()!=1 || targets.iterator().next()!=player) throw error("普通玩家只能传送自己。");
        if (!storageHealthy) throw error("ATP 数据保存失败，暂时无法传送，请联系管理员。");
        if(!changes.isEmpty()||!portalChecks.isEmpty())throw error("路网变化正在处理，请稍后重试。");
        if (!ServerPlayNetworking.canSend(player,Protocol.TP_QUERY)) throw error("需要安装客户端 Advanced Teleport 才能使用受限传送。");
        if (world(target.dimension()) == null || Math.abs(target.x())>29_999_984 || Math.abs(target.z())>29_999_984) throw error("目标坐标无效。");
        if (clock-lastRequest.getOrDefault(player.getUuid(),-100L)<10) throw error("请稍候再发送传送请求。");
        lastRequest.put(player.getUuid(),clock);
        Point start = point(player);
        Point expected = engine().resolve(start,target,config.mode.equals("strict")).orElseThrow(() -> error(
                config.mode.equals("strict") ? "严格模式需要靠近已注册站点，且目标必须位于已知路网或城市中。" : "你不在联结区域内，或路网未连接到目标维度。"));
        Pending request = new Pending(++nextRequest,snapshot().revision(),clock+config.clientResponseTimeoutTicks,start,target,expected);
        pending.put(player.getUuid(),request);
        var buf = PacketByteBufs.create(); buf.writeLong(request.id());buf.writeLong(request.revision());
        Protocol.writePoint(buf,start); Protocol.writePoint(buf,target);
        ServerPlayNetworking.send(player,Protocol.TP_QUERY,buf);
        message(player,"正在核对路网并计算传送位置……");
    }

    public void reply(ServerPlayerEntity player,long id,long revision,Point normal,Point strict) {
        Pending request = pending.get(player.getUuid());
        if (request==null || request.id()!=id) return;
        pending.remove(player.getUuid());
        if (!enabled() || !storageHealthy || !changes.isEmpty() || !portalChecks.isEmpty() || request.deadline()<clock || revision!=request.revision() || revision!=snapshot().revision()) {
            sync(player);message(player,"路网数据已更新，请同步完成后重试。");return;
        }
        if (distanceSquared(request.source(),point(player))>1) { message(player,"计算期间位置发生变化，请重新传送。");return; }
        Point proposal = config.mode.equals("strict") ? strict : normal;
        Point verified = engine().resolve(point(player),request.target(),config.mode.equals("strict")).orElse(null);
        if (proposal==null || verified==null || distanceSquared(proposal,verified)>1e-6
                || distanceSquared(request.expected(),verified)>1e-6) {
            message(player,"客户端计算与服务端不一致，请重新同步。");sync(player);return;
        }
        Point landing = safeLanding(player,verified,request.source(),config.mode.equals("strict"));
        if (landing==null) {message(player,"最近的可达位置没有安全落脚点，传送取消。");return;}
        player.stopRiding();
        player.teleport(world(landing.dimension()),landing.x(),landing.y(),landing.z(),player.getYaw(),player.getPitch());
        player.fallDistance=0;
        message(player,String.format(Locale.ROOT,"已传送至可达位置 %.2f %.2f %.2f",landing.x(),landing.y(),landing.z()));
    }
    private Point safeLanding(ServerPlayerEntity player,Point target,Point source,boolean strict) {
        ServerWorld world=world(target.dimension()); if(world==null)return null;
        // Load only the chosen destination chunk, never during road detection.
        world.getChunk(BlockPos.ofFloored(target.x(),target.y(),target.z()));
        for(int offset : new int[]{0,1,-1,2,-2,3,-3,4,-4,8,-8,16,-16}) {
            double y=Math.floor(target.y())+offset;
            Point p=new Point(target.dimension(),target.x(),y,target.z());
            if(strict && Math.abs(y-target.y())>1.5)continue;
            Point reachable=engine().resolve(source,p,false).orElse(null);
            if(reachable==null || distanceSquared(reachable,p)>1.01)continue;
            BlockPos feet=BlockPos.ofFloored(p.x(),p.y(),p.z());
            if(!world.getWorldBorder().contains(feet)||world.isOutOfHeightLimit(feet)||world.isOutOfHeightLimit(feet.up()))continue;
            if(!world.getBlockState(feet).getFluidState().isEmpty()||!world.getBlockState(feet.up()).getFluidState().isEmpty())continue;
            var floor=world.getBlockState(feet.down());
            if(floor.getCollisionShape(world,feet.down()).isEmpty()||floor.isOf(Blocks.MAGMA_BLOCK)||floor.isOf(Blocks.CAMPFIRE)
                    ||floor.isOf(Blocks.SOUL_CAMPFIRE)||floor.isOf(Blocks.CACTUS)||world.getBlockState(feet).isOf(Blocks.FIRE))continue;
            double half=player.getWidth()/2.0;
            if(world.isSpaceEmpty(player,new Box(p.x()-half,y,p.z()-half,p.x()+half,y+player.getHeight(),p.z()+half)))return p;
        }
        return null;
    }
    public static double distanceSquared(Point a,Point b) {
        if(a==null||b==null||!a.dimension().equals(b.dimension()))return Double.POSITIVE_INFINITY;
        return (a.x()-b.x())*(a.x()-b.x())+(a.y()-b.y())*(a.y()-b.y())+(a.z()-b.z())*(a.z()-b.z());
    }
    public static Point findPortal(ServerWorld world,BlockPos center) {
        BlockPos best=null; double distance=Double.POSITIVE_INFINITY;
        for(BlockPos pos:BlockPos.iterate(center.add(-3,-3,-3),center.add(3,3,3))) {
            if(!world.isChunkLoaded(pos))continue;
            var state=world.getBlockState(pos);
            if(state.isOf(Blocks.NETHER_PORTAL)||state.isOf(Blocks.END_PORTAL)) {
                double d=pos.getSquaredDistance(center);
                if(d<distance){best=pos.toImmutable();distance=d;}
            }
        }
        return best==null?null:new Point(world.getRegistryKey().getValue().toString(),best.getX()+.5,best.getY(),best.getZ()+.5);
    }
    public void recordPortal(Point first,Point second) {
        first=portalAnchor(first);second=portalAnchor(second);
        String a=portalKey(first),b=portalKey(second);
        if(a.compareTo(b)>0){Point swap=first;first=second;second=swap;String s=a;a=b;b=s;}
        String id="auto:portal:"+UUID.nameUUIDFromBytes((a+"|"+b).getBytes(StandardCharsets.UTF_8));
        if(entryCount(snapshot())>=config.maxGraphEntries&&snapshot().portals().stream().noneMatch(p->p.id().equals(id)))return;
        if(editor.putAutoPortal(new PortalLink(id,first,second,false)))schedulePortalChecks();
    }
    /** End portals arrive on a platform/spawn instead of another portal block. */
    public void recordPortalTravel(Point entrance,Point landing,Point exit) {
        if(exit!=null){recordPortal(entrance,exit);return;}
        ServerWorld from=world(entrance.dimension());
        if(from==null||!from.getBlockState(BlockPos.ofFloored(entrance.x(),entrance.y(),entrance.z())).isOf(Blocks.END_PORTAL))return;
        String id="auto:arrival:"+UUID.nameUUIDFromBytes((portalKey(entrance)+"|"+portalKey(landing)).getBytes(StandardCharsets.UTF_8));
        if(entryCount(snapshot())>=config.maxGraphEntries&&snapshot().portals().stream().noneMatch(p->p.id().equals(id)))return;
        editor.putAutoPortal(new PortalLink(id,entrance,landing,false));
    }
    private Point portalAnchor(Point point) {
        ServerWorld world=world(point.dimension());if(world==null)return point;
        BlockPos pos=BlockPos.ofFloored(point.x(),point.y(),point.z());
        if(!world.isChunkLoaded(pos)||!world.getBlockState(pos).isOf(Blocks.NETHER_PORTAL))return point;
        var axis=world.getBlockState(pos).get(net.minecraft.block.NetherPortalBlock.AXIS);
        for(int i=0;i<21&&world.getBlockState(pos.down()).isOf(Blocks.NETHER_PORTAL);i++)pos=pos.down();
        var direction=axis==net.minecraft.util.math.Direction.Axis.X?net.minecraft.util.math.Direction.WEST:net.minecraft.util.math.Direction.NORTH;
        for(int i=0;i<21;i++){
            BlockPos next=pos.offset(direction);
            if(!world.isChunkLoaded(next)||!world.getBlockState(next).isOf(Blocks.NETHER_PORTAL))break;
            pos=next;
        }
        return new Point(point.dimension(),pos.getX()+.5,pos.getY(),pos.getZ()+.5);
    }
    private void linkExistingPortal(Point entrance) {
        entrance=portalAnchor(entrance);
        String cacheKey=portalKey(entrance);
        if(clock-portalLookups.getOrDefault(cacheKey,-200L)<100)return;
        Point exit=findExistingExit(entrance);
        if(exit!=null){
            // Do not cache a missing exit: POI changes can arrive later in the activation tick.
            portalLookups.put(cacheKey,clock);
            if(portalLookups.size()>4096)portalLookups.entrySet().removeIf(e->clock-e.getValue()>100);
            recordPortal(entrance,exit);
        }
    }
    private Point findExistingExit(Point entrance) {
        ServerWorld from=world(entrance.dimension());
        if(from==null)return null;
        var key=from.getRegistryKey();
        if(!key.equals(net.minecraft.world.World.OVERWORLD)&&!key.equals(net.minecraft.world.World.NETHER))return null;
        BlockPos source=BlockPos.ofFloored(entrance.x(),entrance.y(),entrance.z());
        from.getChunk(source);
        if(!from.getBlockState(source).isOf(Blocks.NETHER_PORTAL))return null;
        ServerWorld destination=server.getWorld(key.equals(net.minecraft.world.World.NETHER)?net.minecraft.world.World.OVERWORLD:net.minecraft.world.World.NETHER);
        if(destination==null)return null;
        double scale=net.minecraft.world.dimension.DimensionType.getCoordinateScaleFactor(from.getDimension(),destination.getDimension());
        BlockPos target=destination.getWorldBorder().clamp(entrance.x()*scale,entrance.y(),entrance.z()*scale);
        var rectangle=destination.getPortalForcer().getPortalRect(target,destination.getRegistryKey().equals(net.minecraft.world.World.NETHER),destination.getWorldBorder());
        if(rectangle.isPresent()){
            BlockPos exit=rectangle.get().lowerLeft;
            return portalAnchor(new Point(destination.getRegistryKey().getValue().toString(),exit.getX()+.5,exit.getY(),exit.getZ()+.5));
        }
        return null;
    }
    private void schedulePortalChecks() {
        for(PortalLink portal:snapshot().portals())
            if(!portal.protectedEntry()&&portal.id().startsWith("auto:portal:")
                    && !portal.first().dimension().equals(portal.second().dimension())
                    && Set.of("minecraft:overworld","minecraft:the_nether").contains(portal.first().dimension())
                    && Set.of("minecraft:overworld","minecraft:the_nether").contains(portal.second().dimension()))
                portalChecks.add(portal.id());
    }
    private void recheckOnePortal() {
        if(portalChecks.isEmpty())return;
        String id=portalChecks.iterator().next();portalChecks.remove(id);
        PortalLink link=snapshot().portals().stream().filter(p->p.id().equals(id)).findFirst().orElse(null);
        if(link==null||link.protectedEntry())return;
        Point fromFirst=findExistingExit(link.first()),fromSecond=findExistingExit(link.second());
        // An undirected graph may retain a genuinely one-way vanilla connection.
        if(distanceSquared(fromFirst,link.second())>1e-6&&distanceSquared(fromSecond,link.first())>1e-6)
            editor.removeAutoPortal(id);
        if(fromFirst!=null)recordPortal(link.first(),fromFirst);
        if(fromSecond!=null)recordPortal(link.second(),fromSecond);
    }
    private String portalKey(Point p){return p.dimension()+":"+(int)Math.floor(p.x())+":"+(int)Math.floor(p.y())+":"+(int)Math.floor(p.z());}
    private void invalidatePortals(Change change) {
        for(var portal:List.copyOf(snapshot().portals())) {
            if(portal.protectedEntry())continue;
            for(Point endpoint:List.of(portal.first(),portal.second())) {
                if(portal.id().startsWith("auto:arrival:")&&endpoint==portal.second())continue;
                if(!endpoint.dimension().equals(change.dimension()))continue;
                BlockPos pos=BlockPos.ofFloored(endpoint.x(),endpoint.y(),endpoint.z());
                if(pos.getSquaredDistance(change.pos())>36)continue;
                ServerWorld world=world(endpoint.dimension());
                if(world!=null && world.isChunkLoaded(pos)) {
                    var state=world.getBlockState(pos);
                    if(!state.isOf(Blocks.NETHER_PORTAL)&&!state.isOf(Blocks.END_PORTAL)
                            &&editor.removeAutoPortal(portal.id()))schedulePortalChecks();
                }
            }
        }
    }
    public void disconnected(ServerPlayerEntity player){pending.remove(player.getUuid());outgoing.remove(player.getUuid());delivered.remove(player.getUuid());lastClientChange.remove(player.getUuid());lastRequest.remove(player.getUuid());AtpCommands.disconnected(player.getUuid());}
    private static CommandSyntaxException error(String text){return new SimpleCommandExceptionType(Text.literal("[ATP] "+text)).create();}
    public static void message(ServerPlayerEntity player,String text){player.sendMessage(Text.literal("[ATP] "+text),false);}
    @Override public void close() throws Exception {
        try {store.save(snapshot());} finally {SERVERS.remove(server);store.close();}
    }
}
