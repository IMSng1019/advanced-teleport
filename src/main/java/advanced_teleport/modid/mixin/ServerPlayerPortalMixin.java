package advanced_teleport.modid.mixin;

import advanced_teleport.graph.GraphData.Point;
import advanced_teleport.runtime.AtpServer;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerPortalMixin {
    @Unique private Point atp$entrance;
    @Inject(method = "moveToWorld", at = @At("HEAD"))
    private void atp$beforePortal(ServerWorld destination, CallbackInfoReturnable<Entity> cir) {
        var player = (ServerPlayerEntity)(Object)this;
        atp$entrance = AtpServer.findPortal(player.getServerWorld(), player.getBlockPos());
    }
    @Inject(method = "moveToWorld", at = @At("RETURN"))
    private void atp$afterPortal(ServerWorld destination, CallbackInfoReturnable<Entity> cir) {
        var player = (ServerPlayerEntity)(Object)this;
        var service = AtpServer.get(player.server);
        if (service != null && atp$entrance != null && cir.getReturnValue() != null) {
            Point exit = AtpServer.findPortal(player.getServerWorld(), player.getBlockPos());
            if (!AtpServer.point(player).dimension().equals(atp$entrance.dimension())) service.recordPortalTravel(atp$entrance,AtpServer.point(player),exit);
        }
        atp$entrance = null;
    }
}
