package advanced_teleport.gametest;

import advanced_teleport.api.AdvancedTeleportApi;
import advanced_teleport.graph.GraphData.*;
import advanced_teleport.modid.Advanced_teleport;
import advanced_teleport.runtime.AtpServer;
import advanced_teleport.storage.DataPaths;
import advanced_teleport.storage.GraphStore;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.dimension.NetherPortal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Runs against a real transformed Fabric server, including Mixins, world events and SQLite. */
public final class AdvancedTeleportGameTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 100)
    public void lifecycleProtectedApiAndPersistence(TestContext context) throws Exception {
        MinecraftServer server=context.getWorld().getServer();
        AtpServer service=AtpServer.get(server);
        context.assertTrue(service!=null,"ATP server lifecycle did not create its world service");
        Path database=database(server);
        context.assertTrue(Files.isRegularFile(database),"ATP did not create the world's SQLite database");
        byte[] header=new byte[16];
        try(var input=Files.newInputStream(database)) {
            context.assertTrue(input.read(header)==16,"SQLite header is incomplete");
        }
        context.assertTrue(new String(header,StandardCharsets.US_ASCII).equals("SQLite format 3\0"),
                "World graph is not a SQLite database");

        String prefix="atp-test:"+UUID.randomUUID()+":";
        String dimension=context.getWorld().getRegistryKey().getValue().toString();
        Point start=new Point(dimension,100_001,64,100_002);
        Point end=new Point(dimension,100_009,64,100_002);
        Point other=new Point("minecraft:the_nether",200_001,64,200_002);
        Point otherEnd=new Point("minecraft:the_nether",200_009,64,200_002);
        try {
            AdvancedTeleportApi.registerCity(server,new City(prefix+"city",dimension,List.of(
                    new Point(dimension,100_000,64,100_000),new Point(dimension,100_010,64,100_000),
                    new Point(dimension,100_010,64,100_010),new Point(dimension,100_000,64,100_010)),false));
            AdvancedTeleportApi.registerRoad(server,new Road(prefix+"road",dimension,List.of(start,end),RoadKind.API,false));
            AdvancedTeleportApi.registerRoad(server,new Road(prefix+"other",other.dimension(),List.of(other,otherEnd),RoadKind.API,false));
            AdvancedTeleportApi.registerStation(server,new Station(prefix+"station",prefix+"road",start,false));
            AdvancedTeleportApi.registerPortal(server,new PortalLink(prefix+"portal",end,other,false));
            Snapshot snapshot=AdvancedTeleportApi.snapshot(server);
            context.assertTrue(snapshot.cities().stream().filter(c->c.id().startsWith(prefix)).allMatch(City::protectedEntry),"API city was not protected");
            context.assertTrue(snapshot.roads().stream().filter(r->r.id().startsWith(prefix)).allMatch(Road::protectedEntry),"API road was not protected");
            context.assertTrue(snapshot.stations().stream().anyMatch(s->s.id().equals(prefix+"station")&&s.protectedEntry()),"API station was not protected");
            context.assertTrue(snapshot.portals().stream().anyMatch(p->p.id().equals(prefix+"portal")&&p.protectedEntry()),"API portal was not protected");
            context.assertTrue(service.engine().resolve(start,otherEnd,false).orElseThrow().equals(otherEnd),
                    "Registered paired portal did not connect dimensions in the live server graph");

            boolean rejected=false;
            try { AdvancedTeleportApi.registerStation(server,new Station(prefix+"invalid",prefix+"road",new Point(dimension,100_001,64,100_008),false)); }
            catch(IllegalArgumentException expected) { rejected=true; }
            context.assertTrue(rejected,"API accepted a station away from its road");
        } catch(Exception | Error failure) {
            cleanup(server,prefix);
            throw failure;
        }

        long expectedRevision=service.snapshot().revision();
        context.addInstantFinalTask(()->{
            context.assertTrue(service.persistedRevision()>=expectedRevision,"Waiting for background SQLite persistence");
            try(GraphStore persisted=new GraphStore(database)) {
                Snapshot saved=persisted.load();
                context.assertTrue(saved.roads().stream().anyMatch(r->r.id().equals(prefix+"road")&&r.protectedEntry()),
                        "Server tick did not persist the protected API road");
                context.assertTrue(saved.stations().stream().anyMatch(s->s.id().equals(prefix+"station")&&s.protectedEntry()),
                        "Server tick did not persist the protected API station");
            } catch(Exception failure) {
                throw new AssertionError("Could not verify persisted ATP data",failure);
            } finally {
                cleanup(server,prefix);
            }
            context.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40)
    public void transformedVanillaCommandPermissionsAndInterception(TestContext context) throws Exception {
        MinecraftServer server=context.getWorld().getServer();
        AtpServer service=AtpServer.get(server);
        context.assertTrue(service!=null,"ATP service is absent");
        String originalMode=service.config().mode;
        var player=new ServerPlayerEntity(server,context.getWorld(),new GameProfile(UUID.randomUUID(),"ATP_Test_Player"),SyncedClientOptions.createDefault());
        var origin=context.getAbsolutePos(new BlockPos(1,2,1));
        player.refreshPositionAndAngles(origin.getX()+.5,origin.getY(),origin.getZ()+.5,0,0);
        var ordinary=server.getCommandSource().withWorld(context.getWorld()).withEntity(player).withPosition(player.getPos()).withLevel(0);
        var operator=ordinary.withLevel(2);
        var dispatcher=server.getCommandManager().getDispatcher();
        var tp=dispatcher.getRoot().getChild("tp");
        var teleport=dispatcher.getRoot().getChild("teleport");
        var pig=context.spawnEntity(EntityType.PIG,new BlockPos(1,2,1));
        pig.setAiDisabled(true);
        var target=context.getAbsolutePos(new BlockPos(5,2,5));
        String command=String.format(Locale.ROOT,"tp %s %.2f %d %.2f",pig.getUuidAsString(),target.getX()+.5,target.getY(),target.getZ()+.5);
        try {
            service.setMode("default");
            context.assertTrue(tp.canUse(ordinary)&&teleport.canUse(ordinary),"Mixin did not grant ordinary players the restricted TP commands");
            context.assertTrue(service.restricts(ordinary),"Ordinary player was not restricted");
            context.assertFalse(service.restricts(operator),"Level-2 operator was restricted");
            var before=pig.getPos();
            boolean intercepted=false;
            try { dispatcher.execute(command,ordinary); }
            catch(CommandSyntaxException expected) { intercepted=expected.getRawMessage().getString().contains("普通玩家只能"); }
            context.assertTrue(intercepted,"Ordinary player could teleport another entity through vanilla TP");
            context.assertTrue(pig.getPos().equals(before),"Rejected ordinary command still moved its target");

            context.assertTrue(dispatcher.execute(command,operator)==1,"Level-2 operator could not execute vanilla TP");
            context.assertTrue(pig.squaredDistanceTo(target.getX()+.5,target.getY(),target.getZ()+.5)<1e-8,
                    "Operator TP was redirected by ATP instead of reaching the requested destination");

            service.setMode("strict");
            context.assertTrue(tp.canUse(ordinary)&&service.restricts(ordinary),"Strict mode did not retain ordinary restricted TP access");
            service.setMode("off");
            context.assertFalse(tp.canUse(ordinary)||teleport.canUse(ordinary),"Off mode did not restore vanilla level-2 command permission");
            context.assertTrue(tp.canUse(operator)&&teleport.canUse(operator),"Off mode removed operator TP permission");
        } finally {
            service.setMode(originalMode);
            pig.discard();
            player.discard();
        }
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 140)
    public void worldChunkEditsDetectAndRemoveBlueIceRoad(TestContext context) {
        MinecraftServer server=context.getWorld().getServer();
        AtpServer service=AtpServer.get(server);
        context.assertTrue(service!=null,"ATP service is absent");
        // Six cross-sections of three-wide blue ice, with raised solid guardrails.
        // No direct detector/editor calls: the actual WorldChunk Mixin must enqueue each edit.
        for(int x=1;x<=6;x++) {
            for(int z=2;z<=4;z++)context.setBlockState(x,1,z,Blocks.BLUE_ICE);
            context.setBlockState(x,2,1,Blocks.STONE);
            context.setBlockState(x,2,5,Blocks.STONE);
        }
        var low=context.getAbsolutePos(new BlockPos(1,1,1));
        var high=context.getAbsolutePos(new BlockPos(6,3,5));
        String dimension=context.getWorld().getRegistryKey().getValue().toString();
        Set<String> detected=new HashSet<>();
        context.runAtTick(50,()->{
            for(Road road:service.snapshot().roads()) {
                if(road.kind()!=RoadKind.ICE||road.protectedEntry()||!road.dimension().equals(dimension))continue;
                if(road.points().stream().allMatch(p->p.x()>=low.getX()&&p.x()<=high.getX()+1
                        &&p.y()>=low.getY()&&p.y()<=high.getY()+1&&p.z()>=low.getZ()&&p.z()<=high.getZ()+1))detected.add(road.id());
            }
            context.assertFalse(detected.isEmpty(),"Real blue-ice placements did not produce automatic roads through WorldChunk updates");
            for(int x=1;x<=6;x++) {
                for(int z=2;z<=4;z++)context.setBlockState(x,1,z,Blocks.AIR);
                context.setBlockState(x,2,1,Blocks.AIR);
                context.setBlockState(x,2,5,Blocks.AIR);
            }
        });
        context.runAtTick(100,()->{
            context.assertTrue(service.snapshot().roads().stream().noneMatch(r->detected.contains(r.id())),
                    "Destroyed blue-ice road remained in the authoritative graph");
            context.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 180)
    public void netherPortalActivationPairsAndDestructionInvalidates(TestContext context) {
        MinecraftServer server=context.getWorld().getServer();
        AtpServer service=AtpServer.get(server);
        ServerWorld overworld=server.getWorld(World.OVERWORLD),nether=server.getWorld(World.NETHER);
        context.assertTrue(service!=null&&overworld!=null&&nether!=null,"Portal test requires ATP and both vanilla dimensions");
        BlockPos sourceFrame=new BlockPos(1000,100,1000),destinationFrame=new BlockPos(125,100,125);
        forceFrame(overworld,sourceFrame,true);
        forceFrame(nether,destinationFrame,true);
        clearFrame(overworld,sourceFrame);
        clearFrame(nether,destinationFrame);
        Runnable portalCleanup=()->{
            clearFrame(overworld,sourceFrame);clearFrame(nether,destinationFrame);
            forceFrame(overworld,sourceFrame,false);forceFrame(nether,destinationFrame,false);
        };
        // These coordinates are fixed to exercise the vanilla 8:1 dimension scaling. Remove
        // leftovers from a previously failed harness run before testing fresh activation.
        for(PortalLink link:List.copyOf(service.snapshot().portals()))
            if(!link.protectedEntry()&&testPortalLink(link,sourceFrame,destinationFrame))
                AdvancedTeleportApi.unregister(server,"portal",link.id());

        context.runAtTick(20,()->{
            try {
                activateFrame(overworld,sourceFrame);
                activateFrame(nether,destinationFrame);
            } catch(RuntimeException | Error failure) {
                portalCleanup.run();
                throw failure;
            }
        });
        Set<String> paired=new HashSet<>();
        context.runAtTick(80,()->{
            try {
                for(PortalLink link:service.snapshot().portals())
                    if(!link.protectedEntry()&&testPortalLink(link,sourceFrame,destinationFrame))paired.add(link.id());
                context.assertFalse(paired.isEmpty(),"Activating real Nether portals did not create an automatic paired graph link");
                // Delete the portal interior through real WorldChunk updates. No graph mutation.
                for(int x=1;x<=2;x++)for(int y=1;y<=3;y++)
                    overworld.setBlockState(sourceFrame.add(x,y,0),Blocks.AIR.getDefaultState(),3);
            } catch(RuntimeException | Error failure) {
                portalCleanup.run();
                throw failure;
            }
        });
        context.runAtTick(130,()->{
            try {
                context.assertTrue(service.snapshot().portals().stream().noneMatch(p->paired.contains(p.id())),
                        "Destroying the real source portal did not remove its automatic cross-dimension link");
            } finally {
                portalCleanup.run();
            }
            context.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 250)
    public void nearerPortalExitsRemoveHistoricalCrossDimensionLink(TestContext context) {
        MinecraftServer server=context.getWorld().getServer();
        AtpServer service=AtpServer.get(server);
        ServerWorld overworld=server.getWorld(World.OVERWORLD),nether=server.getWorld(World.NETHER);
        context.assertTrue(service!=null&&overworld!=null&&nether!=null,"Portal remapping test requires both vanilla dimensions");
        BlockPos a=new BlockPos(2000,100,2000),b=new BlockPos(258,100,250);
        BlockPos c=new BlockPos(250,100,250),d=new BlockPos(2072,100,2000);
        // Stay well inside both search radii, accounting for the interior at X+1.
        overworld.getChunk(a);overworld.getChunk(d);nether.getChunk(b);nether.getChunk(c);
        Runnable cleanup=()->{
            clearFrame(overworld,a);clearFrame(overworld,d);
            clearFrame(nether,b);clearFrame(nether,c);
            forceFrame(overworld,a,false);forceFrame(overworld,d,false);
            forceFrame(nether,b,false);forceFrame(nether,c,false);
        };
        cleanup.run();
        forceFrame(overworld,a,true);forceFrame(overworld,d,true);
        forceFrame(nether,b,true);forceFrame(nether,c,true);
        Set<String> historical=new HashSet<>();
        context.runAtTick(20,()->{
            try { activateFrame(overworld,a); }
            catch(RuntimeException | Error failure) { cleanup.run();throw failure; }
        });
        context.runAtTick(40,()->{
            try { activateFrame(nether,b); }
            catch(RuntimeException | Error failure) { cleanup.run();throw failure; }
        });
        context.runAtTick(70,()->{
            try {
                String diagnostic="A->"+vanillaExit(overworld,nether,a)+"; B->"+vanillaExit(nether,overworld,b)
                        +"; graph="+service.snapshot().portals();
                Advanced_teleport.LOGGER.info("Portal remapping initial lookup: {}",diagnostic);
                for(PortalLink link:service.snapshot().portals())
                    if(!link.protectedEntry()&&testPortalLink(link,a,b))historical.add(link.id());
                context.assertFalse(historical.isEmpty(),"Initial active A/B portals did not establish a paired graph link: "+diagnostic);
                // C is now A's nearer exit; D is now B's nearer exit. Both old
                // portal frames remain intact, so block-destruction checks cannot fix this.
                activateFrame(nether,c);activateFrame(overworld,d);
            } catch(RuntimeException | Error failure) { cleanup.run();throw failure; }
        });
        context.runAtTick(170,()->{
            try {
                Snapshot graph=service.snapshot();
                context.assertTrue(graph.portals().stream().anyMatch(p->!p.protectedEntry()&&testPortalLink(p,a,c)),
                        "Nearer exit C did not form the replacement A/C link");
                context.assertTrue(graph.portals().stream().anyMatch(p->!p.protectedEntry()&&testPortalLink(p,d,b)),
                        "Nearer exit D did not form the replacement D/B link");
                context.assertTrue(graph.portals().stream().noneMatch(p->historical.contains(p.id())||testPortalLink(p,a,b)),
                        "Historical A/B link remained after both live portals selected different nearer exits");
                context.assertTrue(overworld.getBlockState(a.add(1,1,0)).isOf(Blocks.NETHER_PORTAL)
                                &&nether.getBlockState(b.add(1,1,0)).isOf(Blocks.NETHER_PORTAL),
                        "Remapping test accidentally destroyed an old portal");
            } finally { cleanup.run(); }
            context.complete();
        });
    }

    private static void activateFrame(ServerWorld world,BlockPos origin) {
        for(int x=0;x<=3;x++)for(int y=0;y<=4;y++) {
            boolean border=x==0||x==3||y==0||y==4;
            world.setBlockState(origin.add(x,y,0),(border?Blocks.OBSIDIAN:Blocks.AIR).getDefaultState(),3);
        }
        NetherPortal.getNewPortal(world,origin.add(1,1,0),Direction.Axis.X)
                .orElseThrow(()->new AssertionError("Test obsidian frame was rejected by vanilla portal validation"))
                .createPortal();
    }

    private static String vanillaExit(ServerWorld from,ServerWorld destination,BlockPos frame) {
        double scale=net.minecraft.world.dimension.DimensionType.getCoordinateScaleFactor(from.getDimension(),destination.getDimension());
        BlockPos target=destination.getWorldBorder().clamp((frame.getX()+1.5)*scale,frame.getY()+1,(frame.getZ()+.5)*scale);
        return destination.getPortalForcer().getPortalRect(target,destination.getRegistryKey().equals(World.NETHER),destination.getWorldBorder())
                .map(rect->rect.lowerLeft.toShortString()).orElse("none")+" (target "+target.toShortString()+")";
    }

    private static void clearFrame(ServerWorld world,BlockPos origin) {
        for(int x=0;x<=3;x++)for(int y=0;y<=4;y++)world.setBlockState(origin.add(x,y,0),Blocks.AIR.getDefaultState(),3);
    }

    private static void forceFrame(ServerWorld world,BlockPos origin,boolean forced) {
        // Remote portal tests must keep their observed chunks loaded across scheduled assertions.
        for(int x=origin.getX()>>4;x<=((origin.getX()+3)>>4);x++) {
            world.setChunkForced(x,origin.getZ()>>4,forced);
            if(forced)world.getChunk(x,origin.getZ()>>4);
        }
    }

    private static boolean testPortalLink(PortalLink link,BlockPos source,BlockPos destination) {
        return nearFrame(link.first(),World.OVERWORLD.getValue().toString(),source)
                &&nearFrame(link.second(),World.NETHER.getValue().toString(),destination)
                ||nearFrame(link.second(),World.OVERWORLD.getValue().toString(),source)
                &&nearFrame(link.first(),World.NETHER.getValue().toString(),destination);
    }

    private static boolean nearFrame(Point point,String dimension,BlockPos frame) {
        return point.dimension().equals(dimension)&&point.x()>=frame.getX()&&point.x()<=frame.getX()+3
                &&point.y()>=frame.getY()&&point.y()<=frame.getY()+4&&Math.abs(point.z()-frame.getZ())<1;
    }

    private static Path database(MinecraftServer server) {
        String address=java.util.Objects.toString(server.getServerIp(),"");
        String endpoint=server.isDedicated()?(address.isBlank()?"0.0.0.0":address)+":"+server.getServerPort():"singleplayer";
        return DataPaths.worldDirectory(Advanced_teleport.DATA_ROOT,endpoint,AtpServer.get(server).worldName()).resolve("graph.sqlite");
    }

    private static void cleanup(MinecraftServer server,String prefix) {
        for(String type:List.of("station","portal","road","city")) {
            if(type.equals("road"))AdvancedTeleportApi.unregister(server,type,prefix+"other");
            AdvancedTeleportApi.unregister(server,type,prefix+type);
        }
    }
}
