package advanced_teleport.modid.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets="net.minecraft.server.command.TeleportCommand$LookTarget")
public interface TeleportLookTargetInvoker {
    @Invoker("look") void atp$look(ServerCommandSource source,Entity entity);
}
