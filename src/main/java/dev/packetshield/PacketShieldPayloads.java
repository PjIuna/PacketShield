package dev.packetshield;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class PacketShieldPayloads {
    private static boolean registered;

    private PacketShieldPayloads() {}

    public static synchronized void registerTypes() {
        if (registered) return;
        PayloadTypeRegistry.playC2S().register(PositionRestoreRequestPayload.ID, PositionRestoreRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(FallbackChangesPayload.ID, FallbackChangesPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PositionRestoreStatusPayload.ID, PositionRestoreStatusPayload.CODEC);
        registered = true;
    }
}
