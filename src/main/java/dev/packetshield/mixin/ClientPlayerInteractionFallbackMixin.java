package dev.packetshield.mixin;

import dev.packetshield.PacketShieldClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionFallbackMixin {
    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
    private void packetshield$placeOnlyInFallbackWorld(ClientPlayerEntity player, Hand hand, BlockHitResult hit,
                                                        CallbackInfoReturnable<ActionResult> cir) {
        if (!(player.getWorld() instanceof ClientWorld world)
                || !PacketShieldClient.isUsingFallbackWorld(world)) return;

        ItemStack stack = player.getStackInHand(hand);
        if (!(stack.getItem() instanceof BlockItem)
                || player.getItemCooldownManager().isCoolingDown(stack.getItem())) return;

        // Vanilla would send this use packet through the connection that has already
        // closed. Apply its normal client placement locally and retain it for sync.
        // The BlockItem mixin records the successful placement for the server payload.
        boolean placingFinalItem = stack.getCount() == 1;
        ItemStack original = stack.copy();
        ItemStack placementStack = placingFinalItem ? original.copy() : stack;
        if (placingFinalItem) player.setStackInHand(hand, placementStack);

        ActionResult result;
        try {
            result = placementStack.useOnBlock(new ItemUsageContext(player, hand, hit));
        } finally {
            if (placingFinalItem) player.setStackInHand(hand, original);
        }
        if (result.isAccepted()) {
            // Emptying the active hand while its network handler is disconnected can
            // trigger vanilla's stale inventory path. The final placement instead uses
            // a detached stack, then PacketShield sends an empty slot after rejoin.
            if (placingFinalItem && placementStack.isEmpty()) {
                PacketShieldClient.retainFinalFallbackPlacementItem(player, hand, original);
            }
            cir.setReturnValue(result);
        }
    }
}
