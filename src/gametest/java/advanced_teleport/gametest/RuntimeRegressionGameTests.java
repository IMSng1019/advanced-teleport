package advanced_teleport.gametest;

import advanced_teleport.api.AdvancedTeleportApi;
import advanced_teleport.config.AtpConfig;
import advanced_teleport.detection.DetectorSettings;
import advanced_teleport.detection.MinecraftBlockSource;
import advanced_teleport.detection.RoadDetector;
import advanced_teleport.graph.GraphData.*;
import advanced_teleport.graph.GraphEngine;
import advanced_teleport.modid.Advanced_teleport;
import advanced_teleport.network.Protocol;
import advanced_teleport.runtime.AtpServer;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BedPart;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.NetworkState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Real Minecraft/Fabric handlers with an in-memory Netty transport; no production decision is mocked. */
public final class RuntimeRegressionGameTests implements FabricGameTest {
    private static final net.minecraft.server.world.ChunkTicketType<net.minecraft.util.math.ChunkPos> PARTIAL_CHUNK_HOLD=
            net.minecraft.server.world.ChunkTicketType.create("atp_partial_unload_regression",
                    java.util.Comparator.comparingLong(net.minecraft.util.math.ChunkPos::toLong));
    @GameTest(templateName=EMPTY_STRUCTURE,tickLimit=60,batchId="atp_runtime_tp")
    public void ordinaryTpPacketRoundTripPreservesRotationAndIgnoresUnrelatedWork(TestContext context) throws Exception {
        MinecraftServer server=context.getWorld().getServer();
        AtpServer service=AtpServer.get(server);
        AtpConfig original=service.config();
        String prefix="atp-roundtrip:"+UUID.randomUUID()+":";
        String dimension=context.getWorld().getRegistryKey().getValue().toString();
        Point source=new Point(dimension,6001.5,100.75,6001.5);
        Point target=new Point(dimension,6020.25,100.75,6002.5);
        City city=rectangle(prefix+"city",dimension,6000,6000,6008,6008);
        try {
            AtpConfig active=service.config();active.mode="default";active.safeLanding=false;
            active.save(Advanced_teleport.DATA_ROOT.resolve("config.json"));service.reload();
            AdvancedTeleportApi.registerCity(server,city);
            try(TestClient client=new TestClient(server,context.getWorld(),source)) {
                context.assertTrue(ServerPlayNetworking.canSend(client.player,Protocol.TP_QUERY),
                        "The real Fabric minecraft:register handshake did not advertise TP_QUERY");
                Protocol.Sync clientView=copyForClient(service);
                // Unrelated work is intentionally pending at request time, including another dimension.
                service.enqueue(dimension,new BlockPos(26000,100,26000));
                service.enqueue("minecraft:the_nether",new BlockPos(26000,100,26000));
                String command=String.format(Locale.ROOT,"tp %s %.2f %.2f %.2f 137 -23",
                        client.player.getUuidAsString(),target.x(),target.y(),target.z());
                server.getCommandManager().getDispatcher().execute(command,client.source());
                Query query=client.takeQuery();
                context.assertTrue(query.source().equals(source)&&query.target().equals(target),"TP query did not preserve requested points");
                context.assertTrue(client.player.getPos().squaredDistanceTo(source.x(),source.y(),source.z())<1e-8,
                        "Ordinary player moved before a client response");
                // A disconnected graph edit must not invalidate an otherwise identical pending result.
                AdvancedTeleportApi.registerCity(server,rectangle(prefix+"unrelated",dimension,26000,26000,26008,26008));
                context.assertTrue(service.snapshot().revision()>query.revision(),"Fixture did not change the global graph revision");
                client.answer(query,clientView);
                Point expected=new Point(dimension,6008,100.75,6002.5);
                context.assertTrue(AtpServer.distanceSquared(AtpServer.point(client.player),expected)<1e-8,
                        "Unrelated pending work/revision prevented the verified ordinary TP round trip");
                context.assertTrue(Math.abs(MathHelper.wrapDegrees(client.player.getYaw()-137))<1e-4
                                &&Math.abs(client.player.getPitch()+23)<1e-4,
                        "Ordinary TP lost the requested yaw/pitch");
            }
            try(TestClient client=new TestClient(server,context.getWorld(),source)) {
                Protocol.Sync clientView=copyForClient(service);
                // Explicit decimals avoid vanilla Vec3Argument's +0.5 block-centering for integer X/Z.
                String command=String.format(Locale.ROOT,"tp %s %.2f %.2f %.2f facing 6008.0 %.2f 6012.5",
                        client.player.getUuidAsString(),target.x(),target.y(),target.z(),target.y());
                server.getCommandManager().getDispatcher().execute(command,client.source());
                client.answer(client.takeQuery(),clientView);
                context.assertTrue(Math.abs(MathHelper.wrapDegrees(client.player.getYaw()))<1e-4
                                &&Math.abs(client.player.getPitch())<1e-4,
                        "Ordinary TP facing was not applied relative to the final redirected landing point; expected yaw=0 pitch=0, actual yaw="
                                +client.player.getYaw()+" pitch="+client.player.getPitch());
            }
        } finally {
            AdvancedTeleportApi.unregister(server,"city",prefix+"city");
            AdvancedTeleportApi.unregister(server,"city",prefix+"unrelated");
            original.save(Advanced_teleport.DATA_ROOT.resolve("config.json"));service.reload();
        }
        context.complete();
    }

    @GameTest(templateName=EMPTY_STRUCTURE,tickLimit=60,batchId="atp_runtime_tp_sleep")
    public void ordinaryTpWakesSleepingPlayerWithoutReturningToOldBed(TestContext context) throws Exception {
        ServerWorld world=context.getWorld();
        MinecraftServer server=world.getServer();
        AtpServer service=AtpServer.get(server);
        AtpConfig original=service.config();
        String dimension=world.getRegistryKey().getValue().toString();
        String cityId="atp-sleep:"+UUID.randomUUID();
        BlockPos foot=new BlockPos(18001,100,18001),head=foot.east();
        var previousBlocks=new LinkedHashMap<BlockPos,BlockState>();
        world.getChunk(foot);
        // Restore the original blocks even when an assertion fails.
        for(int x=-1;x<=2;x++)for(int z=-1;z<=1;z++)for(int y=-1;y<=2;y++) {
            BlockPos pos=foot.add(x,y,z);previousBlocks.put(pos,world.getBlockState(pos));
        }
        try {
            AtpConfig active=service.config();active.mode="default";active.safeLanding=false;
            active.save(Advanced_teleport.DATA_ROOT.resolve("config.json"));service.reload();
            AdvancedTeleportApi.registerCity(server,rectangle(cityId,dimension,18000,18000,18014,18014));
            for(BlockPos pos:previousBlocks.keySet())
                world.setBlockState(pos,pos.getY()<foot.getY()?Blocks.STONE.getDefaultState():Blocks.AIR.getDefaultState(),2);
            BlockState bed=Blocks.RED_BED.getDefaultState().with(BedBlock.FACING,Direction.EAST);
            world.setBlockState(foot,bed.with(BedBlock.PART,BedPart.FOOT),2);
            world.setBlockState(head,bed.with(BedBlock.PART,BedPart.HEAD),2);
            Point source=new Point(dimension,foot.getX()+.5,foot.getY(),foot.getZ()+.5);
            Point expected=new Point(dimension,18014,101,18008.5);
            try(TestClient client=new TestClient(server,world,source)) {
                try {
                    client.player.sleep(head);
                    context.assertTrue(client.player.isSleeping()&&world.getBlockState(head).get(BedBlock.OCCUPIED),
                            "Fixture did not put the real server player to sleep in the real bed");
                    Protocol.Sync clientView=copyForClient(service);
                    server.getCommandManager().getDispatcher().execute(
                            "tp "+client.player.getUuidAsString()+" 18020.25 101.0 18008.5 137 -23",client.source());
                    client.answer(client.takeQuery(),clientView);
                    context.assertFalse(client.player.isSleeping(),"Ordinary TP left the player sleeping in the old bed");
                    context.assertFalse(world.getBlockState(head).get(BedBlock.OCCUPIED),"Ordinary TP left the old bed occupied");
                    context.assertTrue(AtpServer.distanceSquared(AtpServer.point(client.player),expected)<1e-8,
                            "Waking the sleeping player changed the verified TP landing point");
                    context.assertTrue(Math.abs(MathHelper.wrapDegrees(client.player.getHeadYaw()-137))<1e-4,
                            "Ordinary TP did not update the sleeping player's head yaw");
                    client.player.wakeUp(true,true);
                    context.assertTrue(AtpServer.distanceSquared(AtpServer.point(client.player),expected)<1e-8,
                            "Leaving sleep after TP returned the player to the old bed");
                } finally {
                    if(client.player.isSleeping())client.player.wakeUp(true,true);
                }
            }
        } finally {
            for(var entry:previousBlocks.entrySet())world.setBlockState(entry.getKey(),entry.getValue(),2);
            AdvancedTeleportApi.unregister(server,"city",cityId);
            original.save(Advanced_teleport.DATA_ROOT.resolve("config.json"));service.reload();
        }
        context.complete();
    }

    @GameTest(templateName=EMPTY_STRUCTURE,tickLimit=60,batchId="atp_runtime_tp_falling")
    public void ordinaryTpStopsVerticalMotionForNonFlyingPlayer(TestContext context) throws Exception {
        verifyOrdinaryTpMotion(context,false);
    }

    @GameTest(templateName=EMPTY_STRUCTURE,tickLimit=60,batchId="atp_runtime_tp_elytra")
    public void ordinaryTpPreservesElytraMotion(TestContext context) throws Exception {
        verifyOrdinaryTpMotion(context,true);
    }

    private static void verifyOrdinaryTpMotion(TestContext context,boolean fallFlying) throws Exception {
        ServerWorld world=context.getWorld();
        MinecraftServer server=world.getServer();
        AtpServer service=AtpServer.get(server);
        AtpConfig original=service.config();
        String dimension=world.getRegistryKey().getValue().toString();
        String cityId="atp-motion:"+UUID.randomUUID();
        Point source=new Point(dimension,19001.5,100.75,19001.5);
        Point expected=new Point(dimension,19014,100.75,19008.5);
        Vec3d initialVelocity=new Vec3d(.4,-1.5,.2);
        try {
            AtpConfig active=service.config();active.mode="default";active.safeLanding=false;
            active.save(Advanced_teleport.DATA_ROOT.resolve("config.json"));service.reload();
            AdvancedTeleportApi.registerCity(server,rectangle(cityId,dimension,19000,19000,19014,19014));
            try(TestClient client=new TestClient(server,world,source)) {
                client.player.setOnGround(false);
                if(fallFlying) {
                    client.player.equipStack(EquipmentSlot.CHEST,new ItemStack(Items.ELYTRA));
                    client.player.startFallFlying();
                }
                client.player.setVelocity(initialVelocity);
                context.assertTrue(client.player.isFallFlying()==fallFlying&&!client.player.isOnGround(),
                        "Fixture did not establish the requested airborne state");
                Protocol.Sync clientView=copyForClient(service);
                server.getCommandManager().getDispatcher().execute(
                        "tp "+client.player.getUuidAsString()+" 19020.25 100.75 19008.5",client.source());
                client.answer(client.takeQuery(),clientView);
                context.assertTrue(AtpServer.distanceSquared(AtpServer.point(client.player),expected)<1e-8,
                        "Motion fixture did not complete the ordinary TP packet round trip");
                Vec3d expectedVelocity=fallFlying?initialVelocity:new Vec3d(initialVelocity.x,0,initialVelocity.z);
                context.assertTrue(client.player.getVelocity().squaredDistanceTo(expectedVelocity)<1e-8,
                        fallFlying?"Ordinary TP reset elytra velocity":"Ordinary TP did not stop vertical motion while preserving horizontal motion");
                context.assertTrue(client.player.isOnGround()==!fallFlying,
                        fallFlying?"Ordinary TP grounded an elytra-flying player":"Ordinary TP did not restore the vanilla grounded state");
                context.assertTrue(client.player.isFallFlying()==fallFlying,"Ordinary TP changed the player's elytra flight state");
            }
        } finally {
            AdvancedTeleportApi.unregister(server,"city",cityId);
            original.save(Advanced_teleport.DATA_ROOT.resolve("config.json"));service.reload();
        }
        context.complete();
    }

    @GameTest(templateName=EMPTY_STRUCTURE,tickLimit=60,batchId="atp_runtime_road")
    public void uploadedRoadGeometryIsVerifiedAndReloadRechecksWidth(TestContext context) throws Exception {
        MinecraftServer server=context.getWorld().getServer();
        ServerWorld world=context.getWorld();
        AtpServer service=AtpServer.get(server);
        AtpConfig original=service.config();
        String dimension=world.getRegistryKey().getValue().toString();
        String protectedId="atp-regression:"+UUID.randomUUID();
        BlockPos anchor=new BlockPos(8000,100,8000);
        world.getChunk(anchor);
        try {
            AtpConfig config=service.config();config.iceMinWidth=3;config.iceMaxWidth=5;
            config.save(Advanced_teleport.DATA_ROOT.resolve("config.json"));service.reload();
            for(int x=0;x<5;x++) {
                for(int z=0;z<3;z++)world.setBlockState(anchor.add(x,0,z),Blocks.BLUE_ICE.getDefaultState(),3);
                world.setBlockState(anchor.add(x,1,-1),Blocks.STONE.getDefaultState(),3);
                world.setBlockState(anchor.add(x,1,3),Blocks.STONE.getDefaultState(),3);
                for(int z=0;z<3;z++)for(int y=1;y<=2;y++)world.setBlockState(anchor.add(x,y,z),Blocks.AIR.getDefaultState(),3);
            }
            var detected=RoadDetector.scan(new MinecraftBlockSource(world),dimension,
                    new RoadDetector.Cell(anchor.getX(),anchor.getY(),anchor.getZ()),DetectorSettings.defaults()).roads();
            context.assertFalse(detected.isEmpty(),"Three-wide guarded ice fixture was not valid");
            for(Road road:detected)AdvancedTeleportApi.unregister(server,"road",road.id());
            Road genuine=detected.get(0);
            Road forged=new Road(genuine.id(),dimension,List.of(
                    new Point(dimension,8100,101,8100),new Point(dimension,8101,101,8100)),RoadKind.ICE,false);
            try(TestClient client=new TestClient(server,world,new Point(dimension,8001.5,101,8001.5))) {
                client.sendRoad(new Protocol.RoadProposal(dimension,anchor,service.snapshot().revision(),List.of(forged)));
                context.assertTrue(service.flushRelevantChanges(AtpServer.point(client.player),AtpServer.point(client.player),false),
                        "Forged road proposal validation did not drain its relevant queue");
                context.assertTrue(service.snapshot().roads().stream().noneMatch(r->r.equals(forged)),"Forged uploaded geometry entered the graph");
            }
            for(Road road:detected)AdvancedTeleportApi.unregister(server,"road",road.id());
            try(TestClient client=new TestClient(server,world,new Point(dimension,8001.5,101,8001.5))) {
                long acceptedBefore=service.acceptedRoadProposals();
                client.sendRoad(new Protocol.RoadProposal(dimension,anchor,service.snapshot().revision(),detected));
                context.assertTrue(service.flushRelevantChanges(AtpServer.point(client.player),AtpServer.point(client.player),false),
                        "Valid road proposal validation did not drain its relevant queue");
                context.assertTrue(service.snapshot().roads().containsAll(detected),
                        "The actual ROAD_CHANGE packet did not apply its validated geometry");
                context.assertTrue(service.acceptedRoadProposals()>acceptedBefore,
                        "Roads appeared through fallback scanning without accepting uploaded geometry");
            }
            AdvancedTeleportApi.registerRoad(server,new Road(protectedId,dimension,genuine.points(),RoadKind.API,false));
            config=service.config();config.iceMinWidth=5;
            config.save(Advanced_teleport.DATA_ROOT.resolve("config.json"));service.reload();
            context.assertTrue(service.snapshot().roads().stream().noneMatch(r->detected.stream().anyMatch(old->old.id().equals(r.id()))),
                    "Reload kept three-wide automatic roads after minimum width became five");
            context.assertTrue(service.snapshot().roads().stream().anyMatch(r->r.id().equals(protectedId)&&r.protectedEntry()),
                    "Reload removed a protected API road");
        } finally {
            for(int x=0;x<5;x++)for(int z=-1;z<=3;z++)for(int y=0;y<=2;y++)
                world.setBlockState(anchor.add(x,y,z),Blocks.AIR.getDefaultState(),3);
            AdvancedTeleportApi.unregister(server,"road",protectedId);
            original.save(Advanced_teleport.DATA_ROOT.resolve("config.json"));service.reload();
        }
        context.complete();
    }

    @GameTest(templateName=EMPTY_STRUCTURE,tickLimit=60,batchId="atp_runtime_station")
    public void stationPacketRequiresProviderAuthorizationAndCreatesProtectedStation(TestContext context) {
        MinecraftServer server=context.getWorld().getServer();
        AtpServer service=AtpServer.get(server);
        String token=UUID.randomUUID().toString();
        Identifier provider=new Identifier("atp_regression","station_"+token);
        String roadId="atp_regression:road_"+token,stationId="atp_regression:station_"+token;
        String dimension=context.getWorld().getRegistryKey().getValue().toString();
        Point start=new Point(dimension,9000.5,100,9000.5),end=new Point(dimension,9010.5,100,9000.5);
        try(TestClient owner=new TestClient(server,context.getWorld(),start);
            TestClient stranger=new TestClient(server,context.getWorld(),start)) {
            AdvancedTeleportApi.registerRoad(server,new Road(roadId,dimension,List.of(start,end),RoadKind.API,false));
            AdvancedTeleportApi.registerStationProvider(provider,(player,station)->player==owner.player
                    &&station.roadId().equals(roadId)&&station.position().equals(start));
            Station proposal=new Station(stationId,roadId,start,false);
            stranger.sendStation(new Protocol.StationProposal(provider,proposal));
            context.assertTrue(service.snapshot().stations().stream().noneMatch(s->s.id().equals(stationId)),
                    "An unauthorized player registered a station through the packet receiver");
            owner.sendStation(new Protocol.StationProposal(new Identifier("atp_regression","missing_"+token),proposal));
            context.assertTrue(service.snapshot().stations().stream().noneMatch(s->s.id().equals(stationId)),
                    "Unknown station provider was accepted");
            owner.sendStation(new Protocol.StationProposal(provider,proposal));
            Station accepted=service.snapshot().stations().stream().filter(s->s.id().equals(stationId)).findFirst().orElseThrow();
            context.assertTrue(accepted.protectedEntry(),"Server did not force protection on the accepted station");
            stranger.sendStation(new Protocol.StationProposal(provider,new Station(stationId,roadId,end,false)));
            context.assertTrue(service.snapshot().stations().stream().anyMatch(accepted::equals),
                    "Unauthorized replacement changed a protected station");
        } finally {
            AdvancedTeleportApi.unregister(server,"station",stationId);
            AdvancedTeleportApi.unregister(server,"road",roadId);
        }
        context.complete();
    }

    @GameTest(templateName=EMPTY_STRUCTURE,tickLimit=200,batchId="atp_runtime_unload")
    public void witnessedRoadBreakThenChunkUnloadInvalidatesOnlyAutomaticData(TestContext context) {
        MinecraftServer server=context.getWorld().getServer();
        ServerWorld world=context.getWorld();
        AtpServer service=AtpServer.get(server);
        String dimension=world.getRegistryKey().getValue().toString();
        BlockPos anchor=new BlockPos(14000,100,14000);
        int chunkX=anchor.getX()>>4,chunkZ=anchor.getZ()>>4;
        String protectedId="atp-unload:"+UUID.randomUUID();
        Runnable release=()->{
            world.setChunkForced(chunkX,chunkZ,false);
            world.setChunkForced(chunkX,chunkZ-1,false);
        };
        Runnable cleanup=()->{
            // Reload only after the unloaded-state assertions have succeeded.
            world.getChunk(anchor);
            for(int x=0;x<5;x++)for(int z=-1;z<=3;z++)for(int y=0;y<=2;y++)
                world.setBlockState(anchor.add(x,y,z),Blocks.AIR.getDefaultState(),3);
            AdvancedTeleportApi.unregister(server,"road",protectedId);
            release.run();
        };
        try {
            world.setChunkForced(chunkX,chunkZ,true);
            world.setChunkForced(chunkX,chunkZ-1,true);
            world.getChunk(anchor);
            for(int x=0;x<5;x++) {
                for(int z=0;z<3;z++) {
                    world.setBlockState(anchor.add(x,0,z),Blocks.BLUE_ICE.getDefaultState(),3);
                    for(int y=1;y<=2;y++)world.setBlockState(anchor.add(x,y,z),Blocks.AIR.getDefaultState(),3);
                }
                world.setBlockState(anchor.add(x,1,-1),Blocks.STONE.getDefaultState(),3);
                world.setBlockState(anchor.add(x,1,3),Blocks.STONE.getDefaultState(),3);
            }
            Point near=new Point(dimension,anchor.getX()+2.5,anchor.getY()+1,anchor.getZ()+1.5);
            context.assertTrue(service.flushRelevantChanges(near,near,false),"Initial road detection did not complete");
            List<Road> affected=service.snapshot().roads().stream().filter(r->!r.protectedEntry()&&r.kind()==RoadKind.ICE
                    &&r.dimension().equals(dimension)&&r.points().stream().anyMatch(p->Math.abs(p.x()-near.x())<1e-6
                    &&Math.abs(p.y()-near.y())<1e-6&&Math.abs(p.z()-near.z())<1e-6)).toList();
            context.assertFalse(affected.isEmpty(),"Remote ice fixture did not create the road segments to invalidate");
            Road sample=affected.get(0);
            AdvancedTeleportApi.registerRoad(server,new Road(protectedId,dimension,sample.points(),RoadKind.API,false));
            // This is a real server WorldChunk edit. Do not invoke the editor or flush its new dirty entry.
            world.setBlockState(anchor.add(2,0,1),Blocks.AIR.getDefaultState(),3);
            release.run();
            context.addInstantFinalTask(()->{
                context.assertFalse(world.isChunkLoaded(chunkX,chunkZ),"Waiting for the remote road chunk to unload");
                context.assertTrue(service.snapshot().roads().stream().noneMatch(r->affected.stream().anyMatch(old->old.id().equals(r.id()))),
                        "An automatic road survived a witnessed break after its chunk unloaded");
                context.assertTrue(service.snapshot().roads().stream().anyMatch(r->r.id().equals(protectedId)&&r.protectedEntry()),
                        "Unloaded-change handling removed the protected API road");
                cleanup.run();
                context.complete();
            });
        } catch(RuntimeException|Error failure) {
            cleanup.run();throw failure;
        }
    }

    @GameTest(templateName=EMPTY_STRUCTURE,tickLimit=200,batchId="atp_runtime_partial_unload")
    public void witnessedRailBreakInvalidatesItsUnloadedParallelLane(TestContext context) {
        MinecraftServer server=context.getWorld().getServer();
        ServerWorld world=context.getWorld();
        AtpServer service=AtpServer.get(server);
        String dimension=world.getRegistryKey().getValue().toString();
        BlockPos left=new BlockPos(16014,100,16000),right=new BlockPos(16016,100,16000);
        var leftChunk=new net.minecraft.util.math.ChunkPos(left);
        var rightChunk=new net.minecraft.util.math.ChunkPos(right);
        String protectedId="atp-partial-unload:"+UUID.randomUUID();
        Runnable releaseForced=()->{
            world.setChunkForced(leftChunk.x,leftChunk.z,false);
            world.setChunkForced(rightChunk.x,rightChunk.z,false);
        };
        Runnable cleanup=()->{
            world.getChunk(left);world.getChunk(right);
            for(BlockPos lane:List.of(left,right))for(int z=0;z<5;z++) {
                world.setBlockState(lane.add(0,0,z),Blocks.AIR.getDefaultState(),3);
                world.setBlockState(lane.add(0,-1,z),Blocks.AIR.getDefaultState(),3);
            }
            AdvancedTeleportApi.unregister(server,"road",protectedId);
            releaseForced.run();
            world.getChunkManager().removeTicket(PARTIAL_CHUNK_HOLD,leftChunk,0,leftChunk);
        };
        try {
            world.setChunkForced(leftChunk.x,leftChunk.z,true);
            world.setChunkForced(rightChunk.x,rightChunk.z,true);
            world.getChunk(left);world.getChunk(right);
            for(BlockPos lane:List.of(left,right))for(int z=0;z<5;z++)
                world.setBlockState(lane.add(0,-1,z),Blocks.REDSTONE_BLOCK.getDefaultState(),3);
            var powered=Blocks.POWERED_RAIL.getDefaultState()
                    .with(net.minecraft.block.PoweredRailBlock.SHAPE,net.minecraft.block.enums.RailShape.NORTH_SOUTH)
                    .with(net.minecraft.block.PoweredRailBlock.POWERED,true);
            for(BlockPos lane:List.of(left,right))for(int z=0;z<5;z++)world.setBlockState(lane.add(0,0,z),powered,3);
            Point near=new Point(dimension,left.getX()+.5,left.getY()+.125,left.getZ()+2.5);
            context.assertTrue(service.flushRelevantChanges(near,near,false),"Initial parallel railway detection did not complete");
            List<Road> affected=service.snapshot().roads().stream().filter(r->!r.protectedEntry()&&r.kind()==RoadKind.RAIL
                    &&r.dimension().equals(dimension)&&r.points().stream().anyMatch(p->Math.abs(p.x()-(right.getX()+.5))<1e-6
                    &&Math.abs(p.y()-near.y())<1e-6&&Math.abs(p.z()-near.z())<1e-6)).toList();
            context.assertFalse(affected.isEmpty(),"The paired right-lane segments were not detected");
            AdvancedTeleportApi.registerRoad(server,new Road(protectedId,dimension,affected.get(0).points(),RoadKind.API,false));
            // FORCED tickets also keep neighbors loaded. Replace them with a radius-zero ticket
            // so the left chunk remains FULL while the adjacent right chunk can truly unload.
            world.getChunkManager().addTicket(PARTIAL_CHUNK_HOLD,leftChunk,0,leftChunk);
            releaseForced.run();
            boolean[] broken={false};
            context.addInstantFinalTask(()->{
                context.assertTrue(world.isChunkLoaded(leftChunk.x,leftChunk.z),"The source chunk must remain loaded");
                context.assertFalse(world.isChunkLoaded(rightChunk.x,rightChunk.z),"Waiting for only the partner chunk to unload");
                if(!broken[0]) {
                    world.setBlockState(left.add(0,0,2),Blocks.AIR.getDefaultState(),3);
                    broken[0]=true;
                }
                context.assertTrue(service.flushRelevantChanges(near,near,false),"Relevant rail edit validation did not finish");
                context.assertFalse(world.isChunkLoaded(rightChunk.x,rightChunk.z),"Road validation force-loaded the unknown partner chunk");
                context.assertTrue(service.snapshot().roads().stream().noneMatch(r->affected.stream().anyMatch(old->old.id().equals(r.id()))),
                        "Witnessed left-lane break preserved stale automatic segments in the unloaded partner lane");
                context.assertTrue(service.snapshot().roads().stream().anyMatch(r->r.id().equals(protectedId)&&r.protectedEntry()),
                        "Partial-unload validation removed protected API geometry");
                cleanup.run();context.complete();
            });
        } catch(RuntimeException|Error failure) {
            cleanup.run();throw failure;
        }
    }

    private static City rectangle(String id,String dimension,double minX,double minZ,double maxX,double maxZ) {
        return new City(id,dimension,List.of(new Point(dimension,minX,100,minZ),new Point(dimension,maxX,100,minZ),
                new Point(dimension,maxX,100,maxZ),new Point(dimension,minX,100,maxZ)),true);
    }

    private static Protocol.Sync copyForClient(AtpServer service) {
        return Protocol.decodeSync(Protocol.encodeSync(service.worldName(),service.config(),null,service.snapshot()),null);
    }

    private record Query(long id,long revision,Point source,Point target) {}

    private static final class TestClient implements AutoCloseable {
        final ServerPlayerEntity player;
        final ServerPlayNetworkHandler handler;
        final EmbeddedChannel channel;

        TestClient(MinecraftServer server,ServerWorld world,Point position) {
            GameProfile profile=new GameProfile(UUID.randomUUID(),"ATP_Wire_Test");
            player=new ServerPlayerEntity(server,world,profile,SyncedClientOptions.createDefault());
            player.refreshPositionAndAngles(position.x(),position.y(),position.z(),47,11);
            ClientConnection connection=new ClientConnection(NetworkSide.SERVERBOUND);
            channel=new EmbeddedChannel(connection);
            channel.attr(ClientConnection.SERVERBOUND_PROTOCOL_KEY).set(NetworkState.PLAY.getHandler(NetworkSide.SERVERBOUND));
            channel.attr(ClientConnection.CLIENTBOUND_PROTOCOL_KEY).set(NetworkState.PLAY.getHandler(NetworkSide.CLIENTBOUND));
            handler=new ServerPlayNetworkHandler(server,connection,player,ConnectedClientData.createDefault(profile));
            PacketByteBuf registration=PacketByteBufs.create();
            registration.writeBytes((Protocol.SNAPSHOT+"\0"+Protocol.TP_QUERY).getBytes(StandardCharsets.US_ASCII));
            receive(new Identifier("minecraft","register"),registration);
            // UUID targets are resolved through the real world entity lookup, just like connected players.
            world.getChunk(player.getBlockPos());
            world.onPlayerConnected(player);
        }

        ServerCommandSource source() {
            return player.server.getCommandSource().withWorld(player.getServerWorld()).withEntity(player)
                    .withPosition(player.getPos()).withLevel(0).withEntityAnchor(EntityAnchorArgumentType.EntityAnchor.FEET);
        }

        void receive(Identifier channelId,PacketByteBuf body) {
            PacketByteBuf wire=PacketByteBufs.create();
            try {
                wire.writeIdentifier(channelId);wire.writeBytes(body);
                handler.onCustomPayload(new CustomPayloadC2SPacket(wire));
                channel.runPendingTasks();
            } finally {wire.release();body.release();}
        }

        Query takeQuery() {
            channel.runPendingTasks();
            Object packet;
            while((packet=channel.readOutbound())!=null) {
                if(!(packet instanceof CustomPayloadS2CPacket custom)||!custom.payload().id().equals(Protocol.TP_QUERY))continue;
                PacketByteBuf wire=PacketByteBufs.create();
                try {
                    custom.write(wire);
                    if(!wire.readIdentifier().equals(Protocol.TP_QUERY))throw new AssertionError("Unexpected TP channel encoding");
                    return new Query(wire.readLong(),wire.readLong(),Protocol.readPoint(wire),Protocol.readPoint(wire));
                } finally {wire.release();}
            }
            throw new AssertionError("Server did not send a TP_QUERY packet");
        }

        void answer(Query query,Protocol.Sync clientView) {
            GraphEngine clientGraph=new GraphEngine(clientView.graph(),clientView.config().graphSettings());
            Optional<Point> normal=clientGraph.resolve(query.source(),query.target(),false);
            Optional<Point> strict=clientGraph.resolve(query.source(),query.target(),true);
            PacketByteBuf response=PacketByteBufs.create();
            response.writeLong(query.id());response.writeLong(query.revision());
            response.writeBoolean(normal.isPresent());normal.ifPresent(p->Protocol.writePoint(response,p));
            response.writeBoolean(strict.isPresent());strict.ifPresent(p->Protocol.writePoint(response,p));
            receive(Protocol.TP_REPLY,response);
        }

        void sendRoad(Protocol.RoadProposal proposal) {
            PacketByteBuf body=PacketByteBufs.create();Protocol.writeRoadProposal(body,proposal);receive(Protocol.ROAD_CHANGE,body);
        }

        void sendStation(Protocol.StationProposal proposal) {
            PacketByteBuf body=PacketByteBufs.create();Protocol.writeStationProposal(body,proposal);receive(Protocol.STATION_PROPOSAL,body);
        }

        @Override public void close() {
            channel.finishAndReleaseAll();
            AtpServer service=AtpServer.get(player.server);if(service!=null)service.disconnected(player);
            player.getServerWorld().removePlayer(player,net.minecraft.entity.Entity.RemovalReason.DISCARDED);
        }
    }
}
