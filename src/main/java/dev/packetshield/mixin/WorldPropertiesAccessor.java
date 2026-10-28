package dev.packetshield.mixin;

import net.minecraft.world.MutableWorldProperties;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(World.class)
public interface WorldPropertiesAccessor {
    @Accessor("properties")
    MutableWorldProperties packetshield$getProperties();
}
