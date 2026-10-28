package dev.packetshield.mixin;

import dev.packetshield.PacketShieldClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {
    @Inject(method = "sendMovementPackets", at = @At("HEAD"), cancellable = true)
    private void packetshield$holdPositionPacketsDuringReconnect(CallbackInfo ci) {
        if (PacketShieldClient.isHoldingPositionForRestore()) ci.cancel();
    }
}
