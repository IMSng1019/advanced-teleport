package advanced_teleport.network;

import advanced_teleport.config.AtpConfig;
import advanced_teleport.graph.GraphData.Snapshot;
import advanced_teleport.graph.GraphDelta;
import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Pure codec used off the game thread. Both compressed and expanded payloads have hard bounds. */
public final class SyncCodec {
    public static final int MAX_EXPANDED_BYTES=64*1024*1024;
    private static final Gson JSON=new Gson();
    public record Envelope(String worldName,AtpConfig config,Snapshot full,GraphDelta delta) {
        public Envelope {
            Objects.requireNonNull(worldName);Objects.requireNonNull(config);
            if(worldName.isBlank()||worldName.length()>1024||(full==null)==(delta==null))throw new IllegalArgumentException("Invalid sync envelope");
            config.validate();
        }
        public Snapshot apply(Snapshot base) {
            Snapshot result=full!=null?full:delta.apply(base);
            long count=(long)result.cities().size()+result.roads().size()+result.stations().size()+result.portals().size();
            if(count>config.maxGraphEntries)throw new IllegalArgumentException("Graph exceeds configured entry limit");
            return result;
        }
    }
    public static byte[] encode(String worldName,AtpConfig config,Snapshot base,Snapshot target) {
        Envelope envelope=new Envelope(worldName,config,base==null?target:null,base==null?null:GraphDelta.between(base,target));
        byte[] plain=JSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
        if(plain.length>MAX_EXPANDED_BYTES)throw new IllegalArgumentException("Graph sync exceeds 64 MiB expanded limit");
        try {
            var output=new ByteArrayOutputStream();
            try(var gzip=new GZIPOutputStream(output)){gzip.write(plain);}
            byte[] bytes=output.toByteArray();
            if(bytes.length>SnapshotAssembly.MAX_BYTES)throw new IllegalArgumentException("Graph sync exceeds compressed limit");
            return bytes;
        } catch(IOException impossible){throw new IllegalStateException(impossible);}
    }
    public static Envelope read(byte[] bytes) {
        try {
            Envelope envelope=JSON.fromJson(new String(unpack(bytes,MAX_EXPANDED_BYTES),StandardCharsets.UTF_8),Envelope.class);
            if(envelope==null)throw new IllegalArgumentException("Empty graph sync");
            return envelope;
        } catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid graph sync",invalid);}
    }
    static byte[] unpack(byte[] bytes,int maximum) {
        if(bytes.length>SnapshotAssembly.MAX_BYTES)throw new IllegalArgumentException("Compressed graph sync exceeds limit");
        try(var gzip=new GZIPInputStream(new ByteArrayInputStream(bytes));var output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int read;
            while((read=gzip.read(buffer))!=-1){
                if(output.size()>maximum-read)throw new IllegalArgumentException("Expanded graph sync exceeds limit");
                output.write(buffer,0,read);
            }
            return output.toByteArray();
        } catch(IOException invalid){throw new IllegalArgumentException("Invalid compressed graph sync",invalid);}
    }
    private SyncCodec() {}
}
