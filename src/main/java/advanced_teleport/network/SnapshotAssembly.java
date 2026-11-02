package advanced_teleport.network;

import java.io.ByteArrayOutputStream;

/** Bounded, ordered assembly: a partial snapshot is never exposed to gameplay. */
public final class SnapshotAssembly {
    public static final int CHUNK_BYTES = 24_000;
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    private long transfer = -1;
    private int next, total;
    private ByteArrayOutputStream bytes;

    public byte[] accept(long id, int index, int count, byte[] chunk) {
        if (count < 1 || count > (MAX_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES
                || index < 0 || index >= count || chunk.length > CHUNK_BYTES) {
            throw new IllegalArgumentException("Invalid snapshot chunk bounds");
        }
        if (index == 0) {
            transfer = id; next = 0; total = count; bytes = new ByteArrayOutputStream();
        }
        if (bytes == null || id != transfer || index != next || count != total
                || bytes.size() + chunk.length > MAX_BYTES) {
            bytes = null;
            throw new IllegalArgumentException("Out-of-order or oversized snapshot");
        }
        bytes.writeBytes(chunk);
        next++;
        if (next != total) return null;
        byte[] complete = bytes.toByteArray();
        bytes = null;
        return complete;
    }
}
