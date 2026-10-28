package dev.packetshield.mixin;
import dev.packetshield.PacketShieldClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ClientCommonNetworkHandler.class)
public abstract class ClientCommonNetworkHandlerMixin {
    @Shadow @Final protected MinecraftClient client;
    @Shadow protected boolean transferring;
    @Inject(method="onDisconnected", at=@At("HEAD"))
    private void packetshield$captureDisconnect(DisconnectionInfo info, CallbackInfo ci) {
        if ((Object)this instanceof ClientPlayNetworkHandler play && client.player != null) {
            if (transferring) {
                PacketShieldClient.captureServerTransfer(play, client.world);
            } else {
                PacketShieldClient.captureDisconnect(play, play.getServerInfo(), client.world,
                        client.player.getX(), client.player.getY(),
                        client.player.getZ(), client.player.getYaw(), client.player.getPitch(), info.reason());
            }
        }
    }
}
