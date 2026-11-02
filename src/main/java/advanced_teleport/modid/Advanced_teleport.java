package advanced_teleport.modid;

import net.fabricmc.api.ModInitializer;
import advanced_teleport.runtime.AtpServer;
import advanced_teleport.runtime.AtpCommands;
import advanced_teleport.network.Protocol;
import advanced_teleport.storage.DataPaths;
import advanced_teleport.api.AdvancedTeleportApi;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Advanced_teleport implements ModInitializer {
	public static final String MOD_ID = "advanced_teleport";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static Path DATA_ROOT;

	@Override
	public void onInitialize() {
		var loader=FabricLoader.getInstance();
		var origin=loader.getModContainer(MOD_ID).orElseThrow().getOrigin().getPaths().get(0);
		DATA_ROOT=DataPaths.dataRoot(origin,loader.getGameDir());
		try { Files.createDirectories(DATA_ROOT); }
		catch(Exception e){throw new IllegalStateException("Cannot create ATP data directory: "+DATA_ROOT,e);}
		ServerLifecycleEvents.SERVER_STARTING.register(server->{
			try {new AtpServer(server,DATA_ROOT);}
			catch(Exception e){throw new IllegalStateException("Cannot load ATP world data",e);}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server->{
			var service=AtpServer.get(server);
			if(service!=null)try{service.close();}catch(Exception e){LOGGER.error("Cannot close ATP database",e);}
		});
		ServerTickEvents.END_SERVER_TICK.register(server->{var service=AtpServer.get(server);if(service!=null)service.tick();});
		ServerChunkEvents.CHUNK_LOAD.register((world,chunk)->{var service=AtpServer.get(world.getServer());if(service!=null)service.chunkLoaded(world,chunk);});
		ServerChunkEvents.CHUNK_UNLOAD.register((world,chunk)->{var service=AtpServer.get(world.getServer());if(service!=null)service.chunkUnloaded(world,chunk);});
		ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{var service=AtpServer.get(server);if(service!=null)service.sync(handler.player);});
		ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{var service=AtpServer.get(server);if(service!=null)service.disconnected(handler.player);});
		CommandRegistrationCallback.EVENT.register((dispatcher,registry,environment)->AtpCommands.register(dispatcher));
		ServerPlayNetworking.registerGlobalReceiver(Protocol.ROAD_CHANGE,(server,player,handler,buf,response)->{
			try {
				var proposal=Protocol.readRoadProposal(buf);
				server.execute(()->{var service=AtpServer.get(server);if(service!=null)service.clientProposal(player,proposal);});
			}catch(RuntimeException malformed){LOGGER.debug("Rejected malformed ATP road proposal from {}",player.getUuid());}
		});
		ServerPlayNetworking.registerGlobalReceiver(Protocol.RESYNC,(server,player,handler,buf,response)->
			server.execute(()->{var service=AtpServer.get(server);if(service!=null)service.sync(player);}));
		ServerPlayNetworking.registerGlobalReceiver(Protocol.STATION_PROPOSAL,(server,player,handler,buf,response)->{
			try{
				var proposal=Protocol.readStationProposal(buf);
				server.execute(()->{
					try{AdvancedTeleportApi.acceptStationProposal(player,proposal.provider(),proposal.station());AtpServer.message(player,"站点已通过服务端校验并注册。");}
					catch(RuntimeException rejected){AtpServer.message(player,"站点注册失败："+rejected.getMessage());}
				});
			}catch(RuntimeException malformed){LOGGER.debug("Rejected malformed ATP station proposal from {}",player.getUuid());}
		});
		ServerPlayNetworking.registerGlobalReceiver(Protocol.TP_REPLY,(server,player,handler,buf,response)->{
			try {
				long id=buf.readLong(),revision=buf.readLong();
				var normal=buf.readBoolean()?Protocol.readPoint(buf):null;
				var strict=buf.readBoolean()?Protocol.readPoint(buf):null;
				server.execute(()->{var service=AtpServer.get(server);if(service!=null)service.reply(player,id,revision,normal,strict);});
			}catch(RuntimeException malformed){LOGGER.debug("Rejected malformed ATP teleport proposal from {}",player.getUuid());}
		});
	}

	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}
}
