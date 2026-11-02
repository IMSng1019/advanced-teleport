package advanced_teleport.modid.mixin;

import advanced_teleport.graph.GraphData.Point;
import advanced_teleport.runtime.AtpServer;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.argument.PosArgument;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.command.TeleportCommand;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Collection;

@Mixin(TeleportCommand.class)
public abstract class TeleportCommandMixin {
    @Redirect(method = {"method_13763", "method_13764"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/command/ServerCommandSource;hasPermissionLevel(I)Z"))
    private static boolean atp$permission(ServerCommandSource source, int level) {
        AtpServer service = AtpServer.get(source.getServer());
        return source.hasPermissionLevel(level) || (source.isExecutedByPlayer() && service != null && service.enabled());
    }

    @Inject(method = "execute(Lnet/minecraft/server/command/ServerCommandSource;Ljava/util/Collection;Lnet/minecraft/entity/Entity;)I",
            at = @At("HEAD"), cancellable = true)
    private static void atp$entity(ServerCommandSource source, Collection<? extends Entity> targets, Entity destination,
                                  CallbackInfoReturnable<Integer> cir) throws CommandSyntaxException {
        AtpServer service = AtpServer.get(source.getServer());
        if (service != null && service.restricts(source)) {
            service.request(source, targets, AtpServer.point(destination));
            cir.setReturnValue(1);
        }
    }

    @Inject(method = "execute(Lnet/minecraft/server/command/ServerCommandSource;Ljava/util/Collection;Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/command/argument/PosArgument;Lnet/minecraft/command/argument/PosArgument;Lnet/minecraft/server/command/TeleportCommand$LookTarget;)I",
            at = @At("HEAD"), cancellable = true)
    private static void atp$position(ServerCommandSource source, Collection<? extends Entity> targets, ServerWorld world,
                                    PosArgument location, PosArgument rotation, @Coerce Object lookTarget,
                                    CallbackInfoReturnable<Integer> cir) throws CommandSyntaxException {
        AtpServer service = AtpServer.get(source.getServer());
        if (service != null && service.restricts(source)) {
            var p = location.toAbsolutePos(source);
            service.request(source, targets, new Point(world.getRegistryKey().getValue().toString(), p.x, p.y, p.z));
            cir.setReturnValue(1);
        }
    }
}
