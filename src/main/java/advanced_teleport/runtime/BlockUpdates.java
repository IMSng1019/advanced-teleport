package advanced_teleport.runtime;

import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class BlockUpdates {
    @FunctionalInterface public interface ClientListener { void changed(World world, BlockPos pos); }
    public static volatile ClientListener clientListener;

    public static void changed(World world, BlockPos pos, BlockState oldState, BlockState state) {
        if (oldState == null || oldState.equals(state)) return;
        boolean relevant = advanced_teleport.detection.MinecraftBlockSource.isRelevantChange(world,pos,oldState,state);
        if (!relevant) return;
        if (world.isClient) {
            var listener = clientListener;
            if (listener != null) listener.changed(world, pos.toImmutable());
        } else if (world.getServer() != null) {
            AtpServer service = AtpServer.get(world.getServer());
            if (service != null) service.enqueue(world.getRegistryKey().getValue().toString(), pos);
        }
    }

    private static boolean infrastructure(BlockState state) {
        return state.getBlock() instanceof AbstractRailBlock || state.isOf(Blocks.BLUE_ICE)
                || state.isOf(Blocks.NETHER_PORTAL) || state.isOf(Blocks.END_PORTAL);
    }
    private BlockUpdates() {}
}
