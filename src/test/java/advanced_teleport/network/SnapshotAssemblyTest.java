package advanced_teleport.network;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotAssemblyTest {
    @Test void acceptsOrderedChunksAndResetsForANewTransfer() {
        var assembly = new SnapshotAssembly();
        assertNull(assembly.accept(1, 0, 2, new byte[]{1,2}));
        assertArrayEquals(new byte[]{1,2,3}, assembly.accept(1, 1, 2, new byte[]{3}));
        assertArrayEquals(new byte[]{4}, assembly.accept(2, 0, 1, new byte[]{4}));
    }
    @Test void rejectsMissingChunksAndOversizedTransfers() {
        var assembly = new SnapshotAssembly();
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(1,1,2,new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(1,0,Integer.MAX_VALUE,new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(1,0,1,new byte[SnapshotAssembly.CHUNK_BYTES+1]));
    }
}
