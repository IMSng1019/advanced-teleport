package advanced_teleport.modid.client;

import net.fabricmc.api.ClientModInitializer;
import advanced_teleport.config.AtpConfig;
import advanced_teleport.detection.DetectorSettings;
import advanced_teleport.detection.MinecraftBlockSource;
import advanced_teleport.detection.RoadDetector;
import advanced_teleport.graph.GraphData.Point;
import advanced_teleport.graph.GraphData.Road;
import advanced_teleport.graph.GraphData.Snapshot;
import advanced_teleport.graph.GraphEngine;
import advanced_teleport.modid.Advanced_teleport;
import advanced_teleport.network.Protocol;
import advanced_teleport.network.SnapshotAssembly;
import advanced_teleport.runtime.BlockUpdates;
import advanced_teleport.storage.DataPaths;
import advanced_teleport.storage.GraphStore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class Advanced_teleportClient implements ClientModInitializer {
    private static final int MAX_CHANGES=8192, MAX_CHUNK_JOBS=256, MAX_PROPOSALS=1024;
    private final Set<Change> changes=new LinkedHashSet<>();
    private final ArrayDeque<ChunkSeed> chunkSeeds=new ArrayDeque<>();
    private final ArrayDeque<Proposal> proposals=new ArrayDeque<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(task,"ATP client graph/cache");thread.setDaemon(true);return thread;
    });
    private final AtomicReference<SnapshotJob> pendingSnapshot=new AtomicReference<>();
    private final AtomicBoolean workerRunning=new AtomicBoolean();
    private SnapshotAssembly assembly=new SnapshotAssembly();
    private AtpConfig config;
    private Snapshot snapshot;
    private GraphEngine engine;
    private volatile long session;
    private long latestSnapshot;
    private record Change(String dimension,BlockPos pos) {}
    private record Proposal(Change change,String json) {}
    private record SnapshotJob(long session,long order,byte[] bytes,String endpoint) {}
    private static final class ChunkSeed {
        final ClientWorld world;
        final ChunkPos position;
        int section;
        ChunkSeed(ClientWorld world,ChunkPos position) {this.world=world;this.position=position;}
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
                        if(complete!=null) {
                            String endpoint=client.getCurrentServerEntry()==null?"singleplayer":client.getCurrentServerEntry().address;
                            pendingSnapshot.set(new SnapshotJob(session,++latestSnapshot,complete,endpoint));
                            startWorker(client);
                        }
                    } catch(RuntimeException invalid) {
                        Advanced_teleport.LOGGER.warn("Rejected malformed ATP snapshot chunk",invalid);
                    }
                });
            } catch(RuntimeException invalid) {Advanced_teleport.LOGGER.warn("Rejected malformed ATP packet",invalid);}
        });
        ClientPlayNetworking.registerGlobalReceiver(Protocol.TP_QUERY,(client,handler,buf,sender)->{
            try {
                long id=buf.readLong(),revision=buf.readLong();
                Point source=Protocol.readPoint(buf),target=Protocol.readPoint(buf);
                client.execute(()->{
                    if(client.getNetworkHandler()==handler)reply(id,revision,source,target);
                });
            } catch(RuntimeException invalid) {Advanced_teleport.LOGGER.warn("Rejected malformed ATP teleport query",invalid);}
        });
        BlockUpdates.clientListener=this::changed;
        ClientChunkEvents.CHUNK_LOAD.register((world,chunk)->{
            if(chunkSeeds.size()<MAX_CHUNK_JOBS)chunkSeeds.addLast(new ChunkSeed(world,chunk.getPos()));
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void reset() {
        session++;latestSnapshot=0;
        config=null;snapshot=null;engine=null;assembly=new SnapshotAssembly();
        changes.clear();chunkSeeds.clear();proposals.clear();pendingSnapshot.set(null);
    }

    private void startWorker(MinecraftClient client) {
        if(!workerRunning.compareAndSet(false,true))return;
        worker.execute(()->{
            try {
                SnapshotJob job;
                while((job=pendingSnapshot.getAndSet(null))!=null)processSnapshot(client,job);
            } finally {
                workerRunning.set(false);
                if(pendingSnapshot.get()!=null)startWorker(client);
            }
        });
    }

    private void processSnapshot(MinecraftClient client,SnapshotJob job) {
        if(job.session()!=session)return;
        try {
            Protocol.Sync sync=Protocol.JSON.fromJson(new String(job.bytes(),StandardCharsets.UTF_8),Protocol.Sync.class);
            if(sync==null || sync.config()==null || sync.graph()==null || sync.worldName()==null)
                throw new IllegalArgumentException("Incomplete ATP snapshot");
            sync.config().validate();
            Snapshot graph=sync.graph();
            long entries=(long)graph.cities().size()+graph.roads().size()+graph.stations().size()+graph.portals().size();
            if(entries>sync.config().maxGraphEntries)throw new IllegalArgumentException("ATP snapshot exceeds graph limit");
            GraphEngine derived=new GraphEngine(graph,sync.config().graphSettings());
            client.execute(()->{
                if(job.session()!=session || job.order()!=latestSnapshot)return;
                snapshot=graph;config=sync.config();engine=derived;
            });
            if(job.session()!=session)return;
            Path directory=DataPaths.worldDirectory(Advanced_teleport.DATA_ROOT,job.endpoint(),sync.worldName());
            // Cache is never loaded into active permissions; every connection waits for fresh server data.
            try(GraphStore store=new GraphStore(directory.resolve("client-cache.sqlite"))) {store.save(graph);}
            catch(Exception failure) {Advanced_teleport.LOGGER.warn("Could not save ATP client cache",failure);}
        } catch(RuntimeException invalid) {
            Advanced_teleport.LOGGER.warn("Rejected invalid ATP snapshot",invalid);
        }
    }

    private void reply(long id,long revision,Point source,Point target) {
        if(!ClientPlayNetworking.canSend(Protocol.TP_REPLY))return;
        Optional<Point> normal=Optional.empty(),strict=Optional.empty();
        long localRevision=snapshot==null?-1:snapshot.revision();
        if(engine!=null && localRevision==revision) {
            normal=engine.resolve(source,target,false);
            strict=engine.resolve(source,target,true);
        }
        var out=PacketByteBufs.create();
        out.writeLong(id);out.writeLong(localRevision);
        out.writeBoolean(normal.isPresent());normal.ifPresent(point->Protocol.writePoint(out,point));
        out.writeBoolean(strict.isPresent());strict.ifPresent(point->Protocol.writePoint(out,point));
        ClientPlayNetworking.send(Protocol.TP_REPLY,out);
    }

    private void changed(World world,BlockPos pos) {
        // The common hook is also active during world loading; defer use until fresh sync arrives.
        if(changes.size()<MAX_CHANGES)changes.add(new Change(world.getRegistryKey().getValue().toString(),pos.toImmutable()));
    }

    private void tick(MinecraftClient client) {
        if(client.world==null || client.player==null || config==null || engine==null
                || !ClientPlayNetworking.canSend(Protocol.ROAD_CHANGE))return;
        seedOneSection(client.world);
        int budget=Math.min(2,config.maxPendingChangesPerTick);
        while(budget-->0 && !changes.isEmpty() && proposals.size()<64) {
            Change change=changes.iterator().next();changes.remove(change);
            if(!client.world.getRegistryKey().getValue().toString().equals(change.dimension())
                    || !client.world.isChunkLoaded(change.pos()))continue;
            DetectorSettings settings=new DetectorSettings(config.railStraightGap,config.railCornerMinGap,
                    config.railCornerMaxGap,config.iceMinWidth,config.iceMaxWidth);
            BlockPos pos=change.pos();
            var scan=RoadDetector.scan(new MinecraftBlockSource(client.world),change.dimension(),
                    new RoadDetector.Cell(pos.getX(),pos.getY(),pos.getZ()),settings);
            enqueueProposals(change,scan.roads());
        }
        // Vanilla 1.20.4 limits client custom payloads; every proposal is bounded separately.
        for(int i=0;i<2 && !proposals.isEmpty();i++) {
            Proposal proposal=proposals.removeFirst();
            var out=PacketByteBufs.create();
            out.writeString(proposal.change().dimension(),256);out.writeBlockPos(proposal.change().pos());
            out.writeString(proposal.json(),16_000);
            ClientPlayNetworking.send(Protocol.ROAD_CHANGE,out);
        }
    }

    private void enqueueProposals(Change change,List<Road> roads) {
        List<Road> batch=new ArrayList<>();
        for(Road road:roads) {
            batch.add(road);
            String json=Protocol.JSON.toJson(batch);
            if(json.length()>16_000 || json.getBytes(StandardCharsets.UTF_8).length>24_000) {
                batch.remove(batch.size()-1);
                if(!batch.isEmpty())offerProposal(change,Protocol.JSON.toJson(batch));
                batch.clear();batch.add(road);
            }
        }
        if(!batch.isEmpty() || roads.isEmpty())offerProposal(change,Protocol.JSON.toJson(batch));
    }

    private void offerProposal(Change change,String json) {
        if(proposals.size()<MAX_PROPOSALS && json.length()<=16_000
                && json.getBytes(StandardCharsets.UTF_8).length<=24_000)proposals.addLast(new Proposal(change,json));
    }

    private static boolean infrastructure(BlockState state) {
        return state.getBlock() instanceof AbstractRailBlock || state.isOf(Blocks.BLUE_ICE);
    }

    private void seedOneSection(ClientWorld world) {
        if(changes.size()>MAX_CHANGES/2)return;
        while(!chunkSeeds.isEmpty()) {
            ChunkSeed job=chunkSeeds.peekFirst();
            if(job.world!=world || !world.isChunkLoaded(job.position.x,job.position.z)) {chunkSeeds.removeFirst();continue;}
            WorldChunk chunk=world.getChunk(job.position.x,job.position.z);
            var sections=chunk.getSectionArray();
            if(job.section>=sections.length) {chunkSeeds.removeFirst();continue;}
            int sectionIndex=job.section++;
            var section=sections[sectionIndex];
            if(section.isEmpty() || !section.hasAny(Advanced_teleportClient::infrastructure))continue;
            Set<RoadDetector.Cell> tiles=new HashSet<>();
            for(int y=0;y<16;y++)for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
                if(!infrastructure(section.getBlockState(x,y,z)))continue;
                if(!tiles.add(new RoadDetector.Cell(x/4,y,z/4)))continue;
                changed(world,new BlockPos(job.position.getStartX()+x,world.getBottomY()+sectionIndex*16+y,
                        job.position.getStartZ()+z));
            }
            return; // At most one nonempty section (4096 blocks) per frame.
        }
    }
}
