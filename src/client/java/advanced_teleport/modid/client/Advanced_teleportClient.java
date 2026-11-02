package advanced_teleport.modid.client;

import net.fabricmc.api.ClientModInitializer;
import advanced_teleport.config.AtpConfig;
import advanced_teleport.detection.DetectorSettings;
import advanced_teleport.detection.MinecraftBlockSource;
import advanced_teleport.detection.RoadDetector;
import advanced_teleport.graph.GraphData.*;
import advanced_teleport.graph.GraphEngine;
import advanced_teleport.modid.Advanced_teleport;
import advanced_teleport.network.Protocol;
import advanced_teleport.network.SnapshotAssembly;
import advanced_teleport.runtime.BlockUpdates;
import advanced_teleport.storage.DataPaths;
import advanced_teleport.storage.GraphStore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class Advanced_teleportClient implements ClientModInitializer {
    private static final int MAX_CHANGES=8192,MAX_PROPOSALS=1024,MAX_SYNC_JOBS=64;
    private final Set<Change> changes=new LinkedHashSet<>();
    private final Map<ChunkKey,ChunkSeed> chunkSeeds=new LinkedHashMap<>();
    private final ArrayDeque<Protocol.RoadProposal> proposals=new ArrayDeque<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(task,"ATP client graph/cache");thread.setDaemon(true);return thread;
    });
    // Deltas form a chain. Never replace an older pending delta with a newer one.
    private final ConcurrentLinkedQueue<SnapshotJob> jobs=new ConcurrentLinkedQueue<>();
    private final AtomicInteger queuedJobs=new AtomicInteger();
    private final AtomicLong queuedBytes=new AtomicLong();
    private final AtomicBoolean workerRunning=new AtomicBoolean(),needsResync=new AtomicBoolean();
    private SnapshotAssembly assembly=new SnapshotAssembly();
    private AtpConfig config;
    private Snapshot snapshot;
    private GraphEngine engine;
    private volatile long session;
    private long clock,lastResync=-100;
    // These fields belong exclusively to the cache worker.
    private long workingSession=-1;
    private String workingWorld;
    private Snapshot workingSnapshot;
    private GraphStore cache;

    private record Change(String dimension,BlockPos pos) {}
    private record ChunkKey(ClientWorld world,long position) {}
    private record SnapshotJob(long session,byte[] bytes,String endpoint) {}
    private static final class ChunkSeed {
        final ClientWorld world;final ChunkPos position;
        final Set<BlockPos> oldRoadSeeds=new LinkedHashSet<>();
        int section;boolean initialized;
        ChunkSeed(ClientWorld world,ChunkPos position){this.world=world;this.position=position;}
    }

    @Override public void onInitializeClient() {
        ClientPlayConnectionEvents.INIT.register((handler,client)->reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->reset());
        ClientPlayNetworking.registerGlobalReceiver(Protocol.SNAPSHOT,(client,handler,buf,sender)->{
            try {
                long transfer=buf.readLong();int index=buf.readVarInt(),count=buf.readVarInt();
                byte[] chunk=buf.readByteArray(SnapshotAssembly.CHUNK_BYTES);
                client.execute(()->{
                    if(client.getNetworkHandler()!=handler)return;
                    try {
                        byte[] complete=assembly.accept(transfer,index,count,chunk);
                        if(complete==null)return;
                        if(queuedJobs.get()>=MAX_SYNC_JOBS||queuedBytes.get()+complete.length>64L*1024*1024){invalidateSync();return;}
                        String endpoint=client.getCurrentServerEntry()==null?"singleplayer":client.getCurrentServerEntry().address;
                        queuedJobs.incrementAndGet();queuedBytes.addAndGet(complete.length);jobs.add(new SnapshotJob(session,complete,endpoint));startWorker(client);
                    } catch(RuntimeException invalid){Advanced_teleport.LOGGER.warn("Rejected ATP snapshot",invalid);invalidateSync();}
                });
            } catch(RuntimeException invalid){Advanced_teleport.LOGGER.warn("Rejected ATP snapshot packet",invalid);client.execute(this::invalidateSync);}
        });
        ClientPlayNetworking.registerGlobalReceiver(Protocol.TP_QUERY,(client,handler,buf,sender)->{
            try {
                long id=buf.readLong(),revision=buf.readLong();Point source=Protocol.readPoint(buf),target=Protocol.readPoint(buf);
                client.execute(()->{if(client.getNetworkHandler()==handler)reply(id,revision,source,target);});
            } catch(RuntimeException invalid){Advanced_teleport.LOGGER.warn("Rejected ATP teleport query",invalid);}
        });
        BlockUpdates.clientListener=this::changed;
        ClientChunkEvents.CHUNK_LOAD.register((world,chunk)->markChunk(world,chunk.getPos()));
        ClientChunkEvents.CHUNK_UNLOAD.register((world,chunk)->chunkSeeds.remove(new ChunkKey(world,chunk.getPos().toLong())));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client->{if(!worker.isShutdown()){worker.execute(this::closeCache);worker.shutdown();}});
    }

    private void reset() {
        session++;config=null;snapshot=null;engine=null;assembly=new SnapshotAssembly();
        changes.clear();chunkSeeds.clear();proposals.clear();clearJobs();needsResync.set(false);
        if(!worker.isShutdown())worker.execute(this::closeCache);
    }
    private void invalidateSync() {
        session++;config=null;snapshot=null;engine=null;assembly=new SnapshotAssembly();
        clearJobs();needsResync.set(true);
    }
    private void clearJobs(){jobs.clear();queuedJobs.set(0);queuedBytes.set(0);}
    private void startWorker(MinecraftClient client) {
        if(worker.isShutdown())return;
        if(!workerRunning.compareAndSet(false,true))return;
        worker.execute(()->{
            try {
                SnapshotJob job;
                while((job=jobs.poll())!=null){queuedJobs.updateAndGet(value->Math.max(0,value-1));int length=job.bytes().length;queuedBytes.updateAndGet(value->Math.max(0,value-length));processSnapshot(client,job);}
            } finally {workerRunning.set(false);if(!jobs.isEmpty())startWorker(client);}
        });
    }
    private void closeCache() {
        if(cache!=null)try{cache.close();}catch(Exception failure){Advanced_teleport.LOGGER.warn("Could not close ATP cache",failure);}
        cache=null;workingSession=-1;workingWorld=null;workingSnapshot=null;
    }
    private void processSnapshot(MinecraftClient client,SnapshotJob job) {
        if(job.session()!=session)return;
        try {
            var envelope=Protocol.readEnvelope(job.bytes());
            if(workingSession!=job.session()||!envelope.worldName().equals(workingWorld)) {
                closeCache();workingSession=job.session();workingWorld=envelope.worldName();
                Path directory=DataPaths.worldDirectory(Advanced_teleport.DATA_ROOT,job.endpoint(),workingWorld);
                try{cache=new GraphStore(directory.resolve("client-cache.sqlite"));workingSnapshot=cache.load();}
                catch(Exception failure){Advanced_teleport.LOGGER.warn("Could not read ATP cache; waiting for full server graph",failure);if(cache!=null)try{cache.close();}catch(Exception ignored){}cache=null;workingSnapshot=null;}
            }
            Snapshot graph=envelope.apply(workingSnapshot);
            GraphEngine derived=new GraphEngine(graph,envelope.config().graphSettings());
            workingSnapshot=graph;
            client.execute(()->{
                if(job.session()!=session)return;
                // Cached state alone never enables teleportation: this follows a fresh verified envelope.
                snapshot=graph;config=envelope.config();engine=derived;
            });
            if(job.session()==session&&cache!=null)try{cache.save(graph);}
            catch(Exception failure){Advanced_teleport.LOGGER.warn("Could not save ATP cache",failure);}
        } catch(RuntimeException invalid) {
            Advanced_teleport.LOGGER.warn("Cannot apply ATP delta; requesting a full graph",invalid);
            client.execute(()->{if(job.session()==session)invalidateSync();});
        }
    }
    private void reply(long id,long revision,Point source,Point target) {
        if(!ClientPlayNetworking.canSend(Protocol.TP_REPLY))return;
        Optional<Point> normal=Optional.empty(),strict=Optional.empty();
        if(engine!=null){normal=engine.resolve(source,target,false);strict=engine.resolve(source,target,true);}
        // Revision binds this response to its request. Unrelated graph updates may have arrived
        // meanwhile; the server independently verifies both the original and current result.
        var out=PacketByteBufs.create();out.writeLong(id);out.writeLong(revision);
        out.writeBoolean(normal.isPresent());normal.ifPresent(point->Protocol.writePoint(out,point));
        out.writeBoolean(strict.isPresent());strict.ifPresent(point->Protocol.writePoint(out,point));ClientPlayNetworking.send(Protocol.TP_REPLY,out);
    }
    private void markChunk(ClientWorld world,ChunkPos position){chunkSeeds.put(new ChunkKey(world,position.toLong()),new ChunkSeed(world,position));}
    private void changed(World world,BlockPos pos) {
        if(!(world instanceof ClientWorld clientWorld))return;
        if(changes.size()<MAX_CHANGES)changes.add(new Change(world.getRegistryKey().getValue().toString(),pos.toImmutable()));
        else markChunk(clientWorld,new ChunkPos(pos)); // Coalesced recovery, including deleted roads; never silently drop.
    }
    private DetectorSettings settings(){return new DetectorSettings(config.railStraightGap,config.railCornerMinGap,config.railCornerMaxGap,config.iceMinWidth,config.iceMaxWidth);}
    private void tick(MinecraftClient client) {
        clock++;
        if(needsResync.get()&&clock-lastResync>=20&&client.getNetworkHandler()!=null&&ClientPlayNetworking.canSend(Protocol.RESYNC)) {
            needsResync.set(false);lastResync=clock;ClientPlayNetworking.send(Protocol.RESYNC,PacketByteBufs.create());
        }
        if(client.world==null||client.player==null||config==null||engine==null||!ClientPlayNetworking.canSend(Protocol.ROAD_CHANGE))return;
        seedOneSection(client.world);
        int budget=Math.min(2,config.maxPendingChangesPerTick);
        while(budget-->0&&!changes.isEmpty()&&proposals.size()<64) {
            Change change=changes.iterator().next();changes.remove(change);
            if(!client.world.getRegistryKey().getValue().toString().equals(change.dimension())||!client.world.isChunkLoaded(change.pos()))continue;
            BlockPos pos=change.pos();
            var scan=RoadDetector.scan(new MinecraftBlockSource(client.world),change.dimension(),new RoadDetector.Cell(pos.getX(),pos.getY(),pos.getZ()),settings());
            if(!enqueueProposals(change,scan.roads())){changed(client.world,pos);break;}
        }
        for(int i=0;i<2&&!proposals.isEmpty();i++){
            var out=PacketByteBufs.create();Protocol.writeRoadProposal(out,proposals.removeFirst());ClientPlayNetworking.send(Protocol.ROAD_CHANGE,out);
        }
    }
    private boolean enqueueProposals(Change change,List<Road> roads) {
        List<Protocol.RoadProposal> split=new ArrayList<>();List<Road> batch=new ArrayList<>();
        for(Road road:roads){
            batch.add(road);String json=Protocol.JSON.toJson(batch);
            if(batch.size()>64||json.length()>16000||json.getBytes(StandardCharsets.UTF_8).length>24000){
                batch.remove(batch.size()-1);split.add(new Protocol.RoadProposal(change.dimension(),change.pos(),snapshot.revision(),batch));batch=new ArrayList<>();batch.add(road);
            }
        }
        if(!batch.isEmpty()||roads.isEmpty())split.add(new Protocol.RoadProposal(change.dimension(),change.pos(),snapshot.revision(),batch));
        if(proposals.size()+split.size()>MAX_PROPOSALS)return false;
        proposals.addAll(split);return true;
    }
    private static boolean infrastructure(BlockState state){return state.getBlock() instanceof AbstractRailBlock||state.isOf(Blocks.BLUE_ICE);}
    private void seedOneSection(ClientWorld world) {
        if(changes.size()>MAX_CHANGES/2)return;
        while(!chunkSeeds.isEmpty()) {
            var entry=chunkSeeds.entrySet().iterator().next();ChunkSeed job=entry.getValue();
            if(job.world!=world||!world.isChunkLoaded(job.position.x,job.position.z)){chunkSeeds.remove(entry.getKey());continue;}
            if(!job.initialized) {
                job.initialized=true;int radius=settings().scanRadius();String dimension=world.getRegistryKey().getValue().toString();
                // An emptied chunk still needs old nearby segments checked for deletion.
                for(Road road:snapshot.roads())if(!road.protectedEntry()&&road.dimension().equals(dimension))for(Point point:road.points()) {
                    BlockPos pos=BlockPos.ofFloored(point.x(),point.y()-1,point.z());
                    if(pos.getX()>=job.position.getStartX()-radius&&pos.getX()<=job.position.getEndX()+radius
                            &&pos.getZ()>=job.position.getStartZ()-radius&&pos.getZ()<=job.position.getEndZ()+radius)job.oldRoadSeeds.add(pos);
                }
            }
            while(!job.oldRoadSeeds.isEmpty()&&changes.size()<MAX_CHANGES/2){BlockPos pos=job.oldRoadSeeds.iterator().next();job.oldRoadSeeds.remove(pos);changed(world,pos);}
            if(changes.size()>=MAX_CHANGES/2)return;
            WorldChunk chunk=world.getChunk(job.position.x,job.position.z);var sections=chunk.getSectionArray();
            if(job.section>=sections.length){chunkSeeds.remove(entry.getKey());continue;}
            int sectionIndex=job.section++;var section=sections[sectionIndex];
            if(section.isEmpty()||!section.hasAny(Advanced_teleportClient::infrastructure))continue;
            Set<RoadDetector.Cell> tiles=new HashSet<>();
            for(int y=0;y<16;y++)for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
                if(!infrastructure(section.getBlockState(x,y,z))||!tiles.add(new RoadDetector.Cell(x/4,y,z/4)))continue;
                changed(world,new BlockPos(job.position.getStartX()+x,world.getBottomY()+sectionIndex*16+y,job.position.getStartZ()+z));
            }
            return;
        }
    }
}
