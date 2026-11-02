package advanced_teleport.detection;

import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PoweredRailBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import static advanced_teleport.detection.RoadDetector.*;

/** Main-thread adapter that never requests missing chunks. */
public final class MinecraftBlockSource implements BlockSource {
    private final World world;
    public MinecraftBlockSource(World world) { this.world=world; }

    @Override public BlockSample blockAt(int x,int y,int z) {
        BlockPos pos=new BlockPos(x,y,z);
        if(world.isOutOfHeightLimit(pos)) return BlockSample.AIR;
        if(!world.isChunkLoaded(x>>4,z>>4)) return BlockSample.UNLOADED;
        BlockState state=world.getBlockState(pos);
        if(state.isOf(Blocks.BLUE_ICE)) return BlockSample.BLUE_ICE;
        if(state.getBlock() instanceof AbstractRailBlock rail) {
            Kind kind=state.isOf(Blocks.POWERED_RAIL)?Kind.POWERED_RAIL:
                    state.isOf(Blocks.RAIL)?Kind.RAIL:Kind.OTHER_RAIL;
            Shape shape=Shape.valueOf(state.get(rail.getShapeProperty()).name());
            boolean powered=state.contains(PoweredRailBlock.POWERED) && state.get(PoweredRailBlock.POWERED);
            return new BlockSample(kind,shape,powered);
        }
        if(state.isOf(Blocks.NETHER_PORTAL) || state.isOf(Blocks.END_PORTAL))
            return new BlockSample(Kind.PORTAL,Shape.NONE,false);
        return state.isSolidBlock(world,pos)?BlockSample.SOLID:BlockSample.AIR;
    }

    /** Includes guardrail and headroom edits, not only placement of the floor itself. */
    public static boolean isRelevantChange(World world, BlockPos pos, BlockState oldState, BlockState newState) {
        if(relevant(oldState) || relevant(newState)) return true;
        for(int dy=-2;dy<=0;dy++) for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) {
            BlockPos nearby=pos.add(dx,dy,dz);
            if(world.isChunkLoaded(nearby.getX()>>4,nearby.getZ()>>4)
                    && world.getBlockState(nearby).isOf(Blocks.BLUE_ICE)) return true;
        }
        return false;
    }

    private static boolean relevant(BlockState state) {
        return state.getBlock() instanceof AbstractRailBlock || state.isOf(Blocks.BLUE_ICE)
                || state.isOf(Blocks.NETHER_PORTAL) || state.isOf(Blocks.END_PORTAL);
    }
}
