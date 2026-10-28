package dev.packetshield.mixin;

import dev.packetshield.PacketShieldClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;Z)V", at = @At("HEAD"), cancellable = true)
    private void packetshield$keepWorldWhileReconnecting(Screen disconnectionScreen, boolean transferring, CallbackInfo ci) {
        if (PacketShieldClient.consumeStaleDisconnectSuppression()) {
            ci.cancel();
            return;
        }
        if (PacketShieldClient.shouldPreserveWorldForTransfer(transferring)) {
            ci.cancel();
            return;
        }
        if (PacketShieldClient.shouldAllowManualLeave(disconnectionScreen)) {
            PacketShieldClient.onManualLeave();
            return;
        }
        if (PacketShieldClient.shouldKeepWorld() && disconnectionScreen instanceof DisconnectedScreen) {
            PacketShieldClient.onReconnectAttemptFailed();
            ci.cancel();
        }
    }

    @Inject(method = "setScreen(Lnet/minecraft/client/gui/screen/Screen;)V", at = @At("HEAD"), cancellable = true)
    private void packetshield$hideReconnectScreens(Screen screen, CallbackInfo ci) {
        if (PacketShieldClient.shouldAllowManualLeave(screen)) PacketShieldClient.onManualLeave();
        if (PacketShieldClient.shouldSuppressReconnectScreen(screen)) {
            if (screen instanceof DisconnectedScreen) PacketShieldClient.onReconnectAttemptFailed();
            ci.cancel();
        }
    }
}
