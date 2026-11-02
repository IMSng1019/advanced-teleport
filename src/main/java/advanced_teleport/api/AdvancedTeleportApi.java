package advanced_teleport.api;

import advanced_teleport.graph.GraphData.*;
import advanced_teleport.runtime.AtpServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Stable server-thread API. Registrations are protected from all automatic block scans. */
public final class AdvancedTeleportApi {
    @FunctionalInterface public interface StationValidator {
        /** Check the caller's permissions and the provider's real station block/item rules. */
        boolean mayRegister(ServerPlayerEntity player, Station proposed);
    }
    private static final Map<Identifier,StationValidator> STATION_PROVIDERS=new ConcurrentHashMap<>();

    /** Register during the companion mod's server/common initialization. */
    public static void registerStationProvider(Identifier provider,StationValidator validator) {
        java.util.Objects.requireNonNull(provider);java.util.Objects.requireNonNull(validator);
        if(STATION_PROVIDERS.putIfAbsent(provider,validator)!=null)
            throw new IllegalArgumentException("Station provider already registered: "+provider);
    }

    /** Called on the server thread by the packet receiver; never trusts a client protection flag. */
    public static void acceptStationProposal(ServerPlayerEntity player,Identifier provider,Station proposed) {
        var s=service(player.server);
        var validator=STATION_PROVIDERS.get(provider);
        if(validator==null)throw new IllegalArgumentException("服务端未注册站点处理器："+provider);
        if(!proposed.id().startsWith(provider.getNamespace()+":"))
            throw new IllegalArgumentException("站点 ID 必须属于处理器命名空间");
        if(!validator.mayRegister(player,proposed))throw new IllegalArgumentException("站点注册请求未通过处理器校验");
        s.checkRegistration("station",proposed.id());
        s.editor().registerStation(proposed);
    }
    private static AtpServer service(MinecraftServer server) {
        var service=AtpServer.get(server);
        if(service==null)throw new IllegalStateException("ATP world has not started");
        service.requireThread();return service;
    }
    public static Snapshot snapshot(MinecraftServer server){return service(server).snapshot();}
    public static void registerCity(MinecraftServer server,City city){var s=service(server);s.checkRegistration("city",city.id());s.editor().registerCity(city);}
    public static void registerRoad(MinecraftServer server,Road road){var s=service(server);s.checkRegistration("road",road.id());s.editor().registerRoad(road);}
    public static void registerStation(MinecraftServer server,Station station){var s=service(server);s.checkRegistration("station",station.id());s.editor().registerStation(station);}
    public static void registerPortal(MinecraftServer server,PortalLink portal){var s=service(server);s.checkRegistration("portal",portal.id());s.editor().registerPortal(portal);}
    public static boolean unregister(MinecraftServer server,String type,String id){return service(server).editor().remove(type,id);}
    private AdvancedTeleportApi() {}
}
