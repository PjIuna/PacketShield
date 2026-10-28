package dev.packetshield.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientWorld.class)
public interface ClientWorldAccessor {
    @Mutable @Accessor("networkHandler")
    void packetshield$setNetworkHandler(ClientPlayNetworkHandler handler);

    @Mutable @Accessor("clientWorldProperties")
    void packetshield$setProperties(ClientWorld.Properties properties);

    @Accessor("clientWorldProperties")
    ClientWorld.Properties packetshield$getProperties();
}
