package dev.packetshield.mixin;

import dev.packetshield.PacketShieldClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class BlockItemFallbackSyncMixin {
    @Inject(method = "place", at = @At("RETURN"))
    private void packetshield$recordSuccessfulPlacement(ItemPlacementContext context,
                                                         CallbackInfoReturnable<ActionResult> cir) {
        if (!cir.getReturnValue().isAccepted() || !(context.getWorld() instanceof ClientWorld world)) return;
        PacketShieldClient.recordFallbackPlacement(world, context.getBlockPos(), (BlockItem) (Object) this);
    }
}
