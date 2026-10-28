package dev.packetshield.mixin;

import dev.packetshield.PacketShieldClient;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientWorld.class)
public abstract class ClientWorldFallbackSyncMixin {
    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("RETURN"))
    private void packetshield$recordFallbackBlockChange(BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
                                                         CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            ClientWorld world = (ClientWorld) (Object) this;
            PacketShieldClient.recordFallbackBlockChange(world, pos, world.getBlockState(pos));
        }
    }

    @Inject(method = "removeEntity(ILnet/minecraft/entity/Entity$RemovalReason;)V", at = @At("HEAD"))
    private void packetshield$recordFallbackItemPickup(int entityId, Entity.RemovalReason reason, CallbackInfo ci) {
        PacketShieldClient.recordFallbackEntityRemoval((ClientWorld) (Object) this, entityId);
    }
}
