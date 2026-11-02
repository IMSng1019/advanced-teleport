package advanced_teleport.modid.mixin;

import advanced_teleport.runtime.BlockUpdates;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldChunk.class)
public abstract class WorldChunkMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void atp$changed(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> cir) {
        BlockUpdates.changed(((WorldChunk)(Object)this).getWorld(), pos, cir.getReturnValue(), state);
    }
}
