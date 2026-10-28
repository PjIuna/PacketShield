package dev.packetshield.mixin;

import dev.packetshield.PacketShieldClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onPlayerPositionLook", at = @At("TAIL"))
    private void packetshield$stepTowardSavedPosition(PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
        PacketShieldClient.onServerPositionCorrection();
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void packetshield$skipStaleHandlerTick(CallbackInfo ci) {
        if (PacketShieldClient.shouldSkipStalePlayHandler((ClientPlayNetworkHandler)(Object)this)) ci.cancel();
    }

    @Redirect(method = "onGameJoin", at = @At(value = "NEW", target = "net/minecraft/client/world/ClientWorld"))
    private ClientWorld packetshield$reuseCachedChunks(ClientPlayNetworkHandler handler,
            ClientWorld.Properties properties, RegistryKey<World> dimension,
            RegistryEntry<DimensionType> dimensionType, int loadDistance, int simulationDistance,
            Supplier<Profiler> profiler, WorldRenderer renderer, boolean debug, long seed) {
        return PacketShieldClient.reuseCachedWorld(handler, properties, dimension, dimensionType,
                loadDistance, simulationDistance, profiler, renderer, debug, seed);
    }
}
