package advanced_teleport.api;

import advanced_teleport.graph.GraphData.Station;
import advanced_teleport.network.Protocol;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/** Client extension entry point. The named server-side provider decides whether registration is allowed. */
public final class AdvancedTeleportClientApi {
    public static boolean requestStationRegistration(Identifier provider,Station station) {
        if(!MinecraftClient.getInstance().isOnThread())throw new IllegalStateException("Call ATP client API on the client thread");
        if(!ClientPlayNetworking.canSend(Protocol.STATION_PROPOSAL))return false;
        var buffer=PacketByteBufs.create();Protocol.writeStationProposal(buffer,new Protocol.StationProposal(provider,station));
        ClientPlayNetworking.send(Protocol.STATION_PROPOSAL,buffer);return true;
    }
    private AdvancedTeleportClientApi(){}
}
