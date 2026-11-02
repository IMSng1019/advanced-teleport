package advanced_teleport.gametest;

import advanced_teleport.api.AdvancedTeleportApi;
import advanced_teleport.config.AtpConfig;
import advanced_teleport.graph.GraphData.*;
import advanced_teleport.graph.GraphEditor;
import advanced_teleport.runtime.AtpServer;
import advanced_teleport.runtime.PortalTracker;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.dimension.NetherPortal;

import java.util.List;

/** Real portal surfaces and events supplement the pure rectangle geometry regressions. */
public final class PortalRegressionGameTests implements FabricGameTest {
    @GameTest(templateName=EMPTY_STRUCTURE,tickLimit=160)
    public void roadAtFarSideOfWideActualPortalIsConnected(TestContext context) {
        var server=context.getWorld().getServer();
        var service=AtpServer.get(server);
        var overworld=server.getWorld(World.OVERWORLD);var nether=server.getWorld(World.NETHER);
        BlockPos source=new BlockPos(5000,100,5000),destination=new BlockPos(625,100,625);
        String firstId="atp-regression:wide-source",secondId="atp-regression:wide-destination";
        Runnable cleanup=()->{
            clear(overworld,source,10);clear(nether,destination,2);
            AdvancedTeleportApi.unregister(server,"road",firstId);
            AdvancedTeleportApi.unregister(server,"road",secondId);
            force(overworld,source,10,false);force(nether,destination,2,false);
        };
        cleanup.run();
        force(overworld,source,10,true);force(nether,destination,2,true);
        context.runAtTick(20,()->{
            try {
                activate(overworld,source,10);activate(nether,destination,2);
                AdvancedTeleportApi.registerRoad(server,new Road(firstId,World.OVERWORLD.getValue().toString(),
                        List.of(point(overworld,5010.5,101,5002.5),point(overworld,5010.5,101,5008.5)),RoadKind.API,true));
                AdvancedTeleportApi.registerRoad(server,new Road(secondId,World.NETHER.getValue().toString(),
                        List.of(point(nether,626.5,101,625.5),point(nether,636.5,101,625.5)),RoadKind.API,true));
            } catch(RuntimeException|Error failure){cleanup.run();throw failure;}
        });
        context.runAtTick(100,()->{
            try {
                PortalLink link=service.snapshot().portals().stream().filter(p->pair(p,source,destination)).findFirst().orElseThrow();
                Point anchor=link.first().dimension().equals(World.OVERWORLD.getValue().toString())?link.first():link.second();
                Point extent=link.first().dimension().equals(World.OVERWORLD.getValue().toString())?link.firstExtent():link.secondExtent();
                context.assertTrue(extent.x()-anchor.x()==10,"Actual portal extraction lost the ten-block-wide face");
                Point target=point(nether,636.5,101,625.5);
                context.assertTrue(service.engine().resolve(point(overworld,5010.5,101,5002.5),target,false).orElseThrow().equals(target),
                        "Road beside the far portal edge was not connected across dimensions");
            } finally {cleanup.run();}
            context.complete();
        });
    }

    @GameTest(templateName=EMPTY_STRUCTURE,tickLimit=80)
    public void actualLoadedPortalCellAvoidsRoadGridDiscoveryBlindSpot(TestContext context) {
        var server=context.getWorld().getServer();
        var overworld=server.getWorld(World.OVERWORLD);var nether=server.getWorld(World.NETHER);
        BlockPos source=new BlockPos(10000,100,10000),destination=new BlockPos(1250,100,1250);
        Runnable cleanup=()->{
            clear(overworld,source,2);clear(nether,destination,2);
            force(overworld,source,2,false);force(nether,destination,2,false);
        };
        cleanup.run();force(overworld,source,2,true);force(nether,destination,2,true);
        activate(overworld,source,2);activate(nether,destination,2);
        context.runAtTick(20,()->{
            try {
                var settings=new AtpConfig();var editor=new GraphEditor(Snapshot.empty(),settings.graphSettings());
                var tracker=new PortalTracker(server,editor,()->settings);
                BlockPos actual=source.add(1,1,0);
                BlockPos rounded=new BlockPos(Math.floorDiv(actual.getX(),8)*8+4,Math.floorDiv(actual.getY(),4)*4+2,
                        Math.floorDiv(actual.getZ(),8)*8+4);
                context.assertTrue(PortalTracker.findPortal(overworld,rounded)==null,
                        "Fixture must lie beyond the old three-block search around the road grid center");
                tracker.loadedPortal(overworld,actual);
                for(int i=0;i<10;i++)tracker.tick();
                context.assertTrue(editor.snapshot().portals().stream().anyMatch(p->pair(p,source,destination)),
                        "Loading an actual portal block did not discover its existing opposite endpoint");
            } finally {cleanup.run();}
            context.complete();
        });
    }

    @GameTest(templateName=EMPTY_STRUCTURE,tickLimit=100)
    public void destroyingOneExitRechecksTheSurvivingEntranceForAnAlternative(TestContext context) {
        var server=context.getWorld().getServer();
        var overworld=server.getWorld(World.OVERWORLD);var nether=server.getWorld(World.NETHER);
        BlockPos source=new BlockPos(12000,100,12000),firstExit=new BlockPos(1500,100,1500),alternative=new BlockPos(1508,100,1500);
        Runnable cleanup=()->{
            clear(overworld,source,2);clear(nether,firstExit,2);clear(nether,alternative,2);
            force(overworld,source,2,false);force(nether,firstExit,2,false);force(nether,alternative,2,false);
        };
        cleanup.run();force(overworld,source,2,true);force(nether,firstExit,2,true);force(nether,alternative,2,true);
        activate(overworld,source,2);activate(nether,firstExit,2);activate(nether,alternative,2);
        var settings=new AtpConfig();var editor=new GraphEditor(Snapshot.empty(),settings.graphSettings());
        var tracker=new PortalTracker(server,editor,()->settings);
        context.runAtTick(20,()->{
            try {
                tracker.loadedPortal(overworld,source.add(1,1,0));
                for(int i=0;i<10;i++)tracker.tick();
                context.assertTrue(editor.snapshot().portals().stream().anyMatch(p->pair(p,source,firstExit)),
                        "Fixture did not initially select the nearer exit");
                clear(nether,firstExit,2);
                tracker.changed(nether,firstExit.add(1,1,0));
            } catch(RuntimeException|Error failure){cleanup.run();throw failure;}
        });
        context.runAtTick(40,()->{
            try {
                for(int i=0;i<20;i++)tracker.tick();
                context.assertTrue(editor.snapshot().portals().stream().noneMatch(p->pair(p,source,firstExit)),
                        "Destroyed exit retained its old connection");
                context.assertTrue(editor.snapshot().portals().stream().anyMatch(p->pair(p,source,alternative)),
                        "Surviving entrance was not rechecked for its new exit");
            } finally {cleanup.run();}
            context.complete();
        });
    }

    private static void activate(ServerWorld world,BlockPos origin,int width) {
        for(int x=0;x<width+2;x++)for(int y=0;y<5;y++) {
            boolean frame=x==0||x==width+1||y==0||y==4;
            world.setBlockState(origin.add(x,y,0),(frame?Blocks.OBSIDIAN:Blocks.AIR).getDefaultState(),3);
        }
        NetherPortal.getNewPortal(world,origin.add(1,1,0),Direction.Axis.X).orElseThrow().createPortal();
    }

    private static void clear(ServerWorld world,BlockPos origin,int width) {
        for(int x=0;x<width+2;x++)for(int y=0;y<5;y++)world.setBlockState(origin.add(x,y,0),Blocks.AIR.getDefaultState(),3);
    }

    private static void force(ServerWorld world,BlockPos origin,int width,boolean forced) {
        // These remote fixtures have no watching player; a plain getChunk ticket expires after one tick.
        for(int x=origin.getX()>>4;x<=(origin.getX()+width+1)>>4;x++) {
            world.setChunkForced(x,origin.getZ()>>4,forced);
            if(forced)world.getChunk(x,origin.getZ()>>4);
        }
    }

    private static Point point(ServerWorld world,double x,double y,double z){return new Point(world.getRegistryKey().getValue().toString(),x,y,z);}
    private static boolean pair(PortalLink link,BlockPos source,BlockPos destination) {
        return near(link.first(),World.OVERWORLD.getValue().toString(),source)&&near(link.second(),World.NETHER.getValue().toString(),destination)
                ||near(link.second(),World.OVERWORLD.getValue().toString(),source)&&near(link.first(),World.NETHER.getValue().toString(),destination);
    }
    private static boolean near(Point point,String dimension,BlockPos frame) {
        return point.dimension().equals(dimension)&&point.x()>=frame.getX()+1&&point.x()<frame.getX()+2
                &&point.y()>=frame.getY()+1&&point.y()<frame.getY()+2&&Math.abs(point.z()-(frame.getZ()+.5))<.01;
    }
}
