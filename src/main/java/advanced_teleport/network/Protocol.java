package advanced_teleport.network;

import advanced_teleport.config.AtpConfig;
import advanced_teleport.graph.GraphData.*;
import com.google.gson.Gson;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

public final class Protocol {
    public static final Gson JSON = new Gson();
    public static final Identifier SNAPSHOT = id("snapshot_v1");
    public static final Identifier ROAD_CHANGE = id("road_change_v1");
    public static final Identifier TP_QUERY = id("tp_query_v1");
    public static final Identifier TP_REPLY = id("tp_reply_v1");
    public record Sync(String worldName, AtpConfig config, Snapshot graph) {}
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
