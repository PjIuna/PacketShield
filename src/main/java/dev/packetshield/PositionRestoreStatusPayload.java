package dev.packetshield;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record PositionRestoreStatusPayload(boolean positionRestoreAllowed, boolean fallbackSyncAllowed) implements CustomPayload {
    public static final Id<PositionRestoreStatusPayload> ID =
            new Id<>(Identifier.of("packetshield", "position_restore_status"));
    public static final PacketCodec<RegistryByteBuf, PositionRestoreStatusPayload> CODEC = PacketCodec.ofStatic(
            (buf, payload) -> {
                buf.writeBoolean(payload.positionRestoreAllowed());
                buf.writeBoolean(payload.fallbackSyncAllowed());
            },
            buf -> new PositionRestoreStatusPayload(buf.readBoolean(), buf.readBoolean()));

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
