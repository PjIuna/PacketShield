package dev.packetshield.mixin;

import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientWorld.class)
public abstract class ClientWorldTimeMixin {
    @Inject(method = "setTime", at = @At("TAIL"))
    private void packetshield$syncBaseWorldTime(long time, CallbackInfo ci) {
        ClientWorld.Properties base = (ClientWorld.Properties) ((WorldPropertiesAccessor) this).packetshield$getProperties();
        base.setTime(((ClientWorldAccessor) this).packetshield$getProperties().getTime());
    }

    @Inject(method = "setTimeOfDay", at = @At("TAIL"))
    private void packetshield$syncBaseWorldTimeOfDay(long timeOfDay, CallbackInfo ci) {
        ClientWorld.Properties base = (ClientWorld.Properties) ((WorldPropertiesAccessor) this).packetshield$getProperties();
        base.setTimeOfDay(((ClientWorldAccessor) this).packetshield$getProperties().getTimeOfDay());
    }
}
