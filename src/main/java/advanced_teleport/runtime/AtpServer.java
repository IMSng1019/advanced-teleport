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
import java.util.concurrent.*;
import java.util.function.Consumer;

/** All authoritative state and mutations live on the server thread. */
public final class AtpServer implements AutoCloseable {
    private static final Map<MinecraftServer, AtpServer> SERVERS = new IdentityHashMap<>();
    private final MinecraftServer server;
    private final String worldName;
    private final Path configFile;
    private final AsyncGraphStore store;
    private final GraphEditor editor;
    private final PortalTracker portals;
    private AtpConfig config;
    private GraphEngine engine;
    private long engineRevision = -1, configEpoch, clock, nextRequest, nextTransfer, acceptedRoadProposals;
    private final Map<Change,DirtyChange> changes = new LinkedHashMap<>();
    private final Map<String, ChunkJob> chunkJobs = new LinkedHashMap<>();
    private final Map<String,net.minecraft.world.chunk.WorldChunk> loadedChunks=new HashMap<>();
    private final Map<String,Long> completenessAudits=new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Transfer> outgoing = new HashMap<>();
    private final Map<UUID, Delivered> delivered = new HashMap<>();
    private final Map<UUID, PendingEncoding> encoding=new HashMap<>();
    private final Map<UUID, Stamp> failedSync=new HashMap<>();
    private final Map<UUID, long[]> proposalBudgets = new HashMap<>();
    private final Map<UUID, Long> lastRequest = new HashMap<>();
    private final ExecutorService syncWorker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ATP sync encoder");t.setDaemon(true);return t;});
    private final Map<EncodingKey,CompletableFuture<byte[]>> encoded=new LinkedHashMap<>();
    private record Change(String dimension, BlockPos pos) {}
    private static final class DirtyChange {
        final long queuedAt;
        final Map<String,Road> proposals=new LinkedHashMap<>();
        boolean hasProposal, authoritativeEdit;
        DirtyChange(long tick){queuedAt=tick;}
    }
    private record Pending(long id,long revision,long configEpoch,long deadline,Point target,Point expected,
                           float yaw,float pitch,Consumer<ServerPlayerEntity> afterTeleport) {}
    private record Stamp(long revision, long configEpoch) {}
    private record Delivered(Snapshot graph,long epoch) {}
    private record EncodingKey(long baseRevision,long targetRevision,long epoch) {}
    private record PendingEncoding(Stamp stamp,Snapshot graph,CompletableFuture<byte[]> bytes) {}
    private static final class ChunkJob {
        final ServerWorld world; final net.minecraft.world.chunk.WorldChunk chunk;
        final Set<BlockPos> seeds = new LinkedHashSet<>(); int cursor;
        boolean priority;
        ChunkJob(ServerWorld world,net.minecraft.world.chunk.WorldChunk chunk){this.world=world;this.chunk=chunk;}
    }
    private static final class Transfer {
        final long id; final byte[] data; final Stamp stamp; final Snapshot graph; int offset;
        Transfer(long id,byte[] data,Stamp stamp,Snapshot graph){this.id=id;this.data=data;this.stamp=stamp;this.graph=graph;}
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
        store = new AsyncGraphStore(directory.resolve("graph.sqlite"));
        Snapshot loaded = store.initial();
        if(entryCount(loaded)>config.maxGraphEntries)throw new IllegalStateException("Saved graph exceeds maxGraphEntries; increase it in "+configFile);
        editor = new GraphEditor(loaded, config.graphSettings());
        portals=new PortalTracker(server,editor,()->config);
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
        boolean detectionChanged=!detectorSettings(config).equals(detectorSettings(replacement));
        config = replacement; engine = null;
        pending.clear(); configEpoch++;
        if(detectionChanged){
            Set<String> automatic=new HashSet<>();
            for(Road road:snapshot().roads())if(!road.protectedEntry())automatic.add(road.id());
            editor.applyAutomatic(List.of(),automatic);
            changes.clear();chunkJobs.clear();
            for(var chunk:List.copyOf(loadedChunks.values()))
                if(chunk.getWorld() instanceof ServerWorld world)chunkLoaded(world,chunk);
        }
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
        if(changes.size()<8192)changes.computeIfAbsent(new Change(dimension,pos.toImmutable()),k->new DirtyChange(clock));
        else {
            ServerWorld world=world(dimension);
            if(world!=null&&world.isChunkLoaded(pos))chunkLoaded(world,world.getChunk(pos.getX()>>4,pos.getZ()>>4));
        }
    }
    public void blockChanged(String dimension,BlockPos pos){
        enqueue(dimension,pos);
        var change=new Change(dimension,pos.toImmutable());var dirty=changes.get(change);
        if(dirty!=null)dirty.authoritativeEdit=true;
        else {
            invalidateUnverifiedRoads(change);
            ServerWorld world=world(dimension);if(world!=null)portals.changed(world,pos);
        }
    }
    private void invalidateUnverifiedRoads(Change change){
        Set<String> ids=affectedAutomaticIds(change,detectorSettings(config));
        if(!ids.isEmpty())editor.applyAutomatic(List.of(),ids);
    }
    private Set<String> affectedAutomaticIds(Change change,DetectorSettings settings){
        Set<String> ids=new HashSet<>();
        for(Road road:snapshot().roads())if(!road.protectedEntry()&&road.dimension().equals(change.dimension())
                &&RoadDetector.candidateAnchor(change.dimension(),road.id()).map(c->withinScan(c,change.pos(),settings)).orElse(false))ids.add(road.id());
        return ids;
    }
    public void chunkLoaded(ServerWorld world,net.minecraft.world.chunk.WorldChunk chunk) {
        String dimension=world.getRegistryKey().getValue().toString();
        String key=dimension+":"+chunk.getPos().toLong();
        loadedChunks.put(key,chunk);
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
    public void chunkUnloaded(ServerWorld world,net.minecraft.world.chunk.WorldChunk chunk){
        String key=world.getRegistryKey().getValue()+":"+chunk.getPos().toLong();
        loadedChunks.remove(key);chunkJobs.remove(key);completenessAudits.remove(key);
    }
    private static DetectorSettings detectorSettings(AtpConfig config){
        return new DetectorSettings(config.railStraightGap,config.railCornerMinGap,config.railCornerMaxGap,config.iceMinWidth,config.iceMaxWidth);
    }
    private static BlockPos seed(BlockPos p){return new BlockPos(Math.floorDiv(p.getX(),8)*8+4,Math.floorDiv(p.getY(),4)*4+2,Math.floorDiv(p.getZ(),8)*8+4);}
    private void discoverLoadedRoads() {
        if(chunkJobs.isEmpty()||changes.size()>4096)return;
        var entry=chunkJobs.entrySet().stream().filter(e->e.getValue().priority).findFirst().orElseGet(()->chunkJobs.entrySet().iterator().next());
        var job=entry.getValue();
        if(!job.world.isChunkLoaded(job.chunk.getPos().x,job.chunk.getPos().z)){chunkJobs.remove(entry.getKey());return;}
        var sections=job.chunk.getSectionArray();int budget=4096;
        while(budget-->0 && job.cursor<sections.length*4096) {
            int cursor=job.cursor++;var section=sections[cursor>>12];
            if(section.isEmpty()){job.cursor=((cursor>>12)+1)*4096;continue;}
            int x=cursor&15,z=(cursor>>4)&15,y=(cursor>>8)&15;
            var state=section.getBlockState(x,y,z);
            if(state.isOf(Blocks.NETHER_PORTAL)||state.isOf(Blocks.END_PORTAL))
                portals.loadedPortal(job.world,new BlockPos(job.chunk.getPos().getStartX()+x,job.world.getBottomY()+(cursor>>12)*16+y,job.chunk.getPos().getStartZ()+z));
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

    public void clientProposal(ServerPlayerEntity player,Protocol.RoadProposal proposal) {
        requireThread();
        BlockPos pos=proposal.anchor();
        if(!point(player).dimension().equals(proposal.dimension())||player.squaredDistanceTo(pos.getX(),pos.getY(),pos.getZ())>128*128
                ||!player.getServerWorld().isChunkLoaded(pos)||proposal.revision()>snapshot().revision())return;
        long[] rate=proposalBudgets.computeIfAbsent(player.getUuid(),k->new long[]{clock,0});
        if(rate[0]!=clock){rate[0]=clock;rate[1]=0;}
        if(++rate[1]>4)return;
        var settings=detectorSettings(config);
        for(Road road:proposal.roads()){
            var anchor=RoadDetector.candidateAnchor(proposal.dimension(),road.id());
            if(anchor.isEmpty()||!withinScan(anchor.get(),pos,settings))return;
        }
        enqueue(proposal.dimension(),pos);
        var dirty=changes.get(new Change(proposal.dimension(),pos));
        if(dirty==null)return;
        dirty.hasProposal=true;
        for(Road road:proposal.roads())if(dirty.proposals.size()<4096)dirty.proposals.put(road.id(),road);
    }

    private static boolean withinScan(RoadDetector.Cell cell,BlockPos pos,DetectorSettings settings){
        return Math.abs((long)cell.x()-pos.getX())<=settings.scanRadius()
                &&Math.abs((long)cell.z()-pos.getZ())<=settings.scanRadius()
                &&Math.abs((long)cell.y()-pos.getY())<=settings.verticalScanRadius();
    }
    private void processChange(Change change,DirtyChange dirty){
        ServerWorld world=world(change.dimension());
        if(world==null)return;
        if(!world.isChunkLoaded(change.pos())){
            // A witnessed server edit cannot leave old, unverified connections usable after unload.
            // Mere client observations/chunk-load hints never authorize this invalidation.
            if(dirty.authoritativeEdit)invalidateUnverifiedRoads(change);
            portals.changed(world,change.pos());return;
        }
        var settings=detectorSettings(config);var source=new MinecraftBlockSource(world);
        if(dirty.hasProposal){
            List<Road> existing=snapshot().roads().stream().filter(r->!r.protectedEntry()&&r.dimension().equals(change.dimension()))
                    .filter(r->RoadDetector.candidateAnchor(change.dimension(),r.id()).map(c->withinScan(c,change.pos(),settings)).orElse(false)).toList();
            var checked=RoadDetector.validateExistingCandidates(source,change.dimension(),existing,settings);
            Map<String,Road> accepted=new LinkedHashMap<>();checked.accepted().forEach(r->accepted.put(r.id(),r));
            var uploaded=RoadDetector.validateCandidates(source,change.dimension(),List.copyOf(dirty.proposals.values()),settings);
            acceptedRoadProposals+=uploaded.size();
            for(Road road:uploaded)accepted.put(road.id(),road);
            Set<String> examined=new HashSet<>(checked.rejectedIds());examined.addAll(accepted.keySet());
            if(dirty.authoritativeEdit)examined.addAll(checked.unknownIds());
            applyObservations(List.copyOf(accepted.values()),examined);
            scheduleCompletenessScan(world,change.pos());
        }else{
            var p=change.pos();var scan=RoadDetector.scan(source,change.dimension(),new RoadDetector.Cell(p.getX(),p.getY(),p.getZ()),settings);
            Set<String> examined=new HashSet<>(scan.examinedIds());
            if(dirty.authoritativeEdit)examined.addAll(affectedAutomaticIds(change,settings));
            applyObservations(scan.roads(),examined);
        }
        portals.changed(world,change.pos());
    }
    private void scheduleCompletenessScan(ServerWorld world,BlockPos changed){
        // Uploaded lists are candidates, not proof that no other road exists. Coalesce background
        // discovery by loaded chunk so omitted/partial batches cannot permanently hide new roads.
        String dimension=world.getRegistryKey().getValue().toString();
        for(int x=(changed.getX()>>4)-1;x<=(changed.getX()>>4)+1;x++)for(int z=(changed.getZ()>>4)-1;z<=(changed.getZ()>>4)+1;z++){
            if(!world.isChunkLoaded(x,z))continue;
            var chunk=world.getChunk(x,z);String key=dimension+":"+chunk.getPos().toLong();
            if(clock-completenessAudits.getOrDefault(key,-200L)<200)continue;
            completenessAudits.put(key,clock);chunkLoaded(world,chunk);
            var job=chunkJobs.get(key);if(job!=null)job.priority=true;
        }
    }
    private void applyObservations(List<Road> observed,Set<String> examined){
        Snapshot current=snapshot();
        long retained=current.roads().stream().filter(r->r.protectedEntry()||!examined.contains(r.id())).count();
        int room=(int)Math.max(0,config.maxGraphEntries-retained-current.cities().size()-current.stations().size()-current.portals().size());
        Set<String> known=new HashSet<>();current.roads().forEach(r->known.add(r.id()));
        List<Road> accepted=new ArrayList<>();
        for(Road road:observed)if(known.contains(road.id())&&accepted.size()<room)accepted.add(road);
        for(Road road:observed)if(!known.contains(road.id())&&accepted.size()<room)accepted.add(road);
        editor.applyAutomatic(accepted,examined);
    }

    public void tick() {
        clock++;
        discoverLoadedRoads();
        int work = config.maxPendingChangesPerTick;
        while (work-- > 0 && !changes.isEmpty()) {
            var ready=changes.entrySet().stream().filter(e->e.getValue().hasProposal||clock-e.getValue().queuedAt>=3).findFirst().orElse(null);
            if(ready==null)break;
            changes.remove(ready.getKey());processChange(ready.getKey(),ready.getValue());
        }
        portals.tick();
        pending.entrySet().removeIf(entry -> {
            if (entry.getValue().deadline() >= clock) return false;
            var player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player != null) message(player,"传送计算已超时，请等待数据同步后重试。");
            return true;
        });
        if(clock%20==0&&store.persistedRevision()!=snapshot().revision())store.submit(snapshot());
        if (clock % 10 == 0) {
            Stamp current=new Stamp(snapshot().revision(),configEpoch);
            for (var player : server.getPlayerManager().getPlayerList()) {
                Delivered previous=delivered.get(player.getUuid());
                boolean currentAtClient=previous!=null&&previous.graph().revision()==current.revision()&&previous.epoch()==current.configEpoch();
                if (!outgoing.containsKey(player.getUuid())&&!encoding.containsKey(player.getUuid())&&!currentAtClient
                        &&!current.equals(failedSync.get(player.getUuid()))
                        && ServerPlayNetworking.canSend(player,Protocol.SNAPSHOT)) {
                    queueSync(player,previous==null?null:previous.graph());
                }
            }
        }
        finishEncodings();
        pumpTransfers();
    }
    public void sync(ServerPlayerEntity player) {
        delivered.remove(player.getUuid());outgoing.remove(player.getUuid());encoding.remove(player.getUuid());failedSync.remove(player.getUuid());
        queueSync(player,null);
    }
    private void queueSync(ServerPlayerEntity player,Snapshot base) {
        if(!ServerPlayNetworking.canSend(player,Protocol.SNAPSHOT))return;
        Snapshot target=snapshot();AtpConfig settings=config();
        var key=new EncodingKey(base==null?-1:base.revision(),target.revision(),configEpoch);
        var bytes=encoded.computeIfAbsent(key,k->CompletableFuture.supplyAsync(()->Protocol.encodeSync(worldName,settings,base,target),syncWorker));
        while(encoded.size()>8)encoded.remove(encoded.keySet().iterator().next());
        encoding.put(player.getUuid(),new PendingEncoding(new Stamp(target.revision(),configEpoch),target,bytes));
    }
    private void finishEncodings(){
        var iterator=encoding.entrySet().iterator();
        while(iterator.hasNext()){
            var entry=iterator.next();var result=entry.getValue();
            if(!result.bytes().isDone())continue;
            iterator.remove();
            var player=server.getPlayerManager().getPlayer(entry.getKey());if(player==null)continue;
            try{
                byte[] bytes=result.bytes().join();
                outgoing.put(entry.getKey(),new Transfer(++nextTransfer,bytes,result.stamp(),result.graph()));
            }catch(CompletionException failure){
                failedSync.put(entry.getKey(),result.stamp());
                Advanced_teleport.LOGGER.error("Cannot encode ATP graph update",failure.getCause());
                message(player,"路网同步失败，请联系管理员检查日志。");
            }
        }
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
            if (transfer.offset == transfer.data.length) { iterator.remove(); delivered.put(player.getUuid(),new Delivered(transfer.graph,transfer.stamp.configEpoch())); }
        }
    }

    /** Refresh only geometry relevant to this request; unrelated work keeps its normal tick budget. */
    public boolean flushRelevantChanges(Point source,Point target,boolean strict){
        requireThread();
        int remaining=config.maxTeleportUpdates;
        while(true){
            double radius=Math.max(detectorSettings(config).scanRadius()+config.roadConnectionDistance,
                    Math.max(config.portalConnectionDistance,config.roadAccessDistance))+2;
            var influence=new GraphInfluence(engine(),source,target,strict,radius);
            var relevant=changes.entrySet().stream().filter(e->influence.contains(blockPoint(e.getKey()))).toList();
            var entrances=portals.pendingEntrances().stream().filter(influence::contains).toList();
            var links=portals.pendingLinkIds().stream().filter(influence::containsPortal).toList();
            if(relevant.isEmpty()&&entrances.isEmpty()&&links.isEmpty())return true;
            if(remaining<=0)return false;
            for(var entry:relevant){
                if(remaining--<=0)return false;
                changes.remove(entry.getKey());processChange(entry.getKey(),entry.getValue());
            }
            for(Point entrance:entrances){if(remaining--<=0)return false;if(!portals.checkEntrance(entrance))return false;}
            for(String link:links){if(remaining--<=0)return false;portals.checkLink(link);}
        }
    }
    private static Point blockPoint(Change change){return new Point(change.dimension(),change.pos().getX()+.5,change.pos().getY(),change.pos().getZ()+.5);}

    public void request(ServerCommandSource source, Collection<? extends Entity> targets, Point target) throws CommandSyntaxException {
        ServerPlayerEntity player=source.getPlayerOrThrow();
        request(source,targets,target,player.getYaw(),player.getPitch(),null);
    }
    public void request(ServerCommandSource source,Collection<? extends Entity> targets,Point target,float yaw,float pitch,
                        Consumer<ServerPlayerEntity> afterTeleport)throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        if (targets.size()!=1 || targets.iterator().next()!=player) throw error("普通玩家只能传送自己。");
        if (!store.healthy()) throw error("ATP 数据保存失败，暂时无法传送，请联系管理员。");
        if (!ServerPlayNetworking.canSend(player,Protocol.TP_QUERY)||!ServerPlayNetworking.canSend(player,Protocol.SNAPSHOT))
            throw error("需要安装相同版本的客户端 Advanced Teleport 才能使用受限传送。");
        if (world(target.dimension()) == null || Math.abs(target.x())>29_999_984 || Math.abs(target.z())>29_999_984) throw error("目标坐标无效。");
        if (clock-lastRequest.getOrDefault(player.getUuid(),-100L)<10) throw error("请稍候再发送传送请求。");
        lastRequest.put(player.getUuid(),clock);
        Point start = point(player);
        boolean strict=config.mode.equals("strict");
        if(!flushRelevantChanges(start,target,strict))throw error("当前路线的修改正在处理，请稍后重试。");
        Point expected = engine().resolve(start,target,strict).orElseThrow(() -> error(
                config.mode.equals("strict") ? "严格模式需要靠近已注册站点，且目标必须位于已知路网或城市中。" : "你不在联结区域内，或路网未连接到目标维度。"));
        Pending request = new Pending(++nextRequest,snapshot().revision(),configEpoch,clock+config.clientResponseTimeoutTicks,target,expected,
                net.minecraft.util.math.MathHelper.wrapDegrees(yaw),net.minecraft.util.math.MathHelper.wrapDegrees(pitch),afterTeleport);
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
        if (!enabled() || !store.healthy() || request.configEpoch()!=configEpoch || request.deadline()<clock || revision!=request.revision()) {
            sync(player);message(player,"路网数据已更新，请同步完成后重试。");return;
        }
        Point source=point(player);
        if(!flushRelevantChanges(source,request.target(),config.mode.equals("strict"))){message(player,"当前路线的修改尚未处理完成，请重试。");return;}
        Point proposal = config.mode.equals("strict") ? strict : normal;
        Point verified = engine().resolve(source,request.target(),config.mode.equals("strict")).orElse(null);
        if (proposal==null || verified==null || distanceSquared(proposal,verified)>1e-6
                || distanceSquared(request.expected(),verified)>1e-6) {
            message(player,"客户端计算与服务端不一致，请重新同步。");sync(player);return;
        }
        Point landing = config.safeLanding?safeLanding(player,verified,source,config.mode.equals("strict")):verified;
        if (landing==null) {message(player,"最近的可达位置没有安全落脚点，传送取消。");return;}
        if(!net.minecraft.world.World.isValid(BlockPos.ofFloored(landing.x(),landing.y(),landing.z()))){message(player,"图中的目标坐标超出世界允许范围。");return;}
        player.stopRiding();
        player.teleport(world(landing.dimension()),landing.x(),landing.y(),landing.z(),request.yaw(),request.pitch());
        if(request.afterTeleport()!=null)request.afterTeleport().accept(player);
        player.fallDistance=0;
        message(player,String.format(Locale.ROOT,"已传送至可达位置 %.2f %.2f %.2f",landing.x(),landing.y(),landing.z()));
    }
    private Point safeLanding(ServerPlayerEntity player,Point target,Point source,boolean strict) {
        ServerWorld world=world(target.dimension()); if(world==null)return null;
        // Load only the chosen destination chunk, never during road detection.
        world.getChunk(BlockPos.ofFloored(target.x(),target.y(),target.z()));
        for(int step=0;step<=config.safeLandingVerticalRange*2;step++) {
            int offset=step==0?0:((step+1)/2)*(step%2==1?1:-1);
            double y=step==0?target.y():Math.floor(target.y())+offset;
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
    public static Point findPortal(ServerWorld world,BlockPos center){return PortalTracker.findPortal(world,center);}
    public void recordPortalTravel(Point entrance,Point landing,Point exit){portals.recordTravel(entrance,landing,exit);}
    public long persistedRevision(){return store.persistedRevision();}
    public long acceptedRoadProposals(){return acceptedRoadProposals;}
    public void disconnected(ServerPlayerEntity player){
        UUID id=player.getUuid();pending.remove(id);outgoing.remove(id);delivered.remove(id);encoding.remove(id);failedSync.remove(id);
        proposalBudgets.remove(id);lastRequest.remove(id);AtpCommands.disconnected(id);
    }
    private static CommandSyntaxException error(String text){return new SimpleCommandExceptionType(Text.literal("[ATP] "+text)).create();}
    public static void message(ServerPlayerEntity player,String text){player.sendMessage(Text.literal("[ATP] "+text),false);}
    @Override public void close() throws Exception {
        SERVERS.remove(server);syncWorker.shutdownNow();
        try{store.submit(snapshot());}finally{store.close();}
    }
}
