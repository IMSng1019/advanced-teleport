package advanced_teleport.network;

import advanced_teleport.config.AtpConfig;
import advanced_teleport.graph.GraphData.*;
import com.google.gson.Gson;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

public final class Protocol {
    public static final Gson JSON = new Gson();
    public static final Identifier SNAPSHOT = id("snapshot_v2");
    public static final Identifier ROAD_CHANGE = id("road_change_v2");
    public static final Identifier RESYNC = id("resync_v2");
    public static final Identifier STATION_PROPOSAL = id("station_proposal_v1");
    public static final Identifier TP_QUERY = id("tp_query_v1");
    public static final Identifier TP_REPLY = id("tp_reply_v1");
    public record Sync(String worldName, AtpConfig config, Snapshot graph) {}
    public record RoadProposal(String dimension,BlockPos anchor,long revision,List<Road> roads) {
        public RoadProposal {
            if(dimension==null||dimension.isBlank()||dimension.length()>256||revision<0)throw new IllegalArgumentException("Invalid road proposal metadata");
            anchor=Objects.requireNonNull(anchor).toImmutable();roads=List.copyOf(roads);
            if(roads.size()>64)throw new IllegalArgumentException("Too many road candidates");
            for(Road road:roads)if(!road.dimension().equals(dimension)||road.protectedEntry()||!road.id().startsWith("auto:")
                    ||road.points().size()>8)throw new IllegalArgumentException("Invalid automatic road candidate");
        }
    }
    public record StationProposal(Identifier provider,Station station) {
        public StationProposal {Objects.requireNonNull(provider);Objects.requireNonNull(station);}
    }
    public static byte[] encodeSync(String worldName,AtpConfig config,Snapshot base,Snapshot target) {
        return SyncCodec.encode(worldName,config,base,target);
    }
    public static SyncCodec.Envelope readEnvelope(byte[] bytes){return SyncCodec.read(bytes);}
    public static Sync decodeSync(byte[] bytes,Snapshot base){var envelope=readEnvelope(bytes);return new Sync(envelope.worldName(),envelope.config(),envelope.apply(base));}
    public static void writeRoadProposal(PacketByteBuf buf,RoadProposal proposal) {
        String json=JSON.toJson(proposal.roads());checkJson(json,16000,24000);
        buf.writeString(proposal.dimension(),256);buf.writeBlockPos(proposal.anchor());buf.writeLong(proposal.revision());buf.writeString(json,16000);
    }
    public static RoadProposal readRoadProposal(PacketByteBuf buf) {
        String dimension=buf.readString(256);BlockPos anchor=buf.readBlockPos();long revision=buf.readLong();
        String json=buf.readString(16000);checkJson(json,16000,24000);
        Road[] roads=JSON.fromJson(json,Road[].class);
        if(roads==null)throw new IllegalArgumentException("Missing road candidates");
        return new RoadProposal(dimension,anchor,revision,List.of(roads));
    }
    public static void writeStationProposal(PacketByteBuf buf,StationProposal proposal) {
        String json=JSON.toJson(proposal.station());checkJson(json,4096,12000);
        buf.writeString(proposal.provider().toString(),256);buf.writeString(json,4096);
    }
    public static StationProposal readStationProposal(PacketByteBuf buf) {
        Identifier provider=new Identifier(buf.readString(256));String json=buf.readString(4096);checkJson(json,4096,12000);
        return new StationProposal(provider,JSON.fromJson(json,Station.class));
    }
    private static void checkJson(String json,int characters,int bytes) {
        if(json.length()>characters||json.getBytes(StandardCharsets.UTF_8).length>bytes)throw new IllegalArgumentException("ATP payload exceeds limits");
    }
    private static Identifier id(String path) { return new Identifier("advanced_teleport", path); }
    public static void writePoint(PacketByteBuf buf, Point p) {
        buf.writeString(p.dimension(), 256);
        buf.writeDouble(p.x()); buf.writeDouble(p.y()); buf.writeDouble(p.z());
    }
    public static Point readPoint(PacketByteBuf buf) {
        return new Point(buf.readString(256), buf.readDouble(), buf.readDouble(), buf.readDouble());
    }
    private Protocol() {}
}
