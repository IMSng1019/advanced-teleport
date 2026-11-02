package advanced_teleport.api;

import advanced_teleport.graph.GraphData.*;
import advanced_teleport.runtime.AtpServer;
import net.minecraft.server.MinecraftServer;

/** Stable server-thread API. Registrations are protected from all automatic block scans. */
public final class AdvancedTeleportApi {
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
