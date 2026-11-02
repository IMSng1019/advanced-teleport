package advanced_teleport.network;

import advanced_teleport.config.AtpConfig;
import advanced_teleport.graph.GraphData.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class SyncCodecTest {
    @Test void fullAndDeltaRoundTripWithoutUnchangedGeometry() {
        var roads=IntStream.range(0,1000).mapToObj(i->new Road("road"+i,"minecraft:overworld",List.of(
                new Point("minecraft:overworld",i,64,0),new Point("minecraft:overworld",i+1,64,0)),RoadKind.API,true)).toList();
        var before=new Snapshot(1,List.of(),roads,List.of(),List.of());
        var after=new Snapshot(2,List.of(),roads.subList(0,999),List.of(),List.of());
        byte[] full=SyncCodec.encode("世界",new AtpConfig(),null,before);
        byte[] delta=SyncCodec.encode("世界",new AtpConfig(),before,after);
        assertEquals(before,SyncCodec.read(full).apply(null));
        assertEquals(after,SyncCodec.read(delta).apply(before));
        assertTrue(delta.length<full.length/4);
        assertThrows(IllegalArgumentException.class,()->SyncCodec.read(delta).apply(Snapshot.empty()));
    }
    @Test void rejectsMalformedAndExcessivelyExpandedCompressedData() {
        assertThrows(IllegalArgumentException.class,()->SyncCodec.read(new byte[]{1,2,3}));
        byte[] bytes=SyncCodec.encode("x".repeat(500),new AtpConfig(),null,Snapshot.empty());
        assertThrows(IllegalArgumentException.class,()->SyncCodec.unpack(bytes,64));
    }
    @Test void configOnlyDeltaKeepsRevisionAndFullSyncReplacesStaleCache() {
        var current=new Snapshot(7,List.of(),List.of(),List.of(),List.of());
        var changed=new AtpConfig();changed.mode="strict";
        var envelope=SyncCodec.read(SyncCodec.encode("world",changed,current,current));
        assertEquals("strict",envelope.config().mode);assertEquals(current,envelope.apply(current));
        var stale=new Snapshot(999,List.of(),List.of(),List.of(),List.of());
        assertEquals(current,SyncCodec.read(SyncCodec.encode("world",changed,null,current)).apply(stale));
    }
}
