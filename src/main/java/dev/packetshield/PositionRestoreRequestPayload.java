package dev.packetshield;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record PositionRestoreRequestPayload(String dimension, double x, double y, double z,
                                           float yaw, float pitch) implements CustomPayload {
    public static final Id<PositionRestoreRequestPayload> ID =
            new Id<>(Identifier.of("packetshield", "position_restore_request"));
    public static final PacketCodec<RegistryByteBuf, PositionRestoreRequestPayload> CODEC = PacketCodec.ofStatic(
            (buf, payload) -> {
                buf.writeString(payload.dimension(), 256);
                buf.writeDouble(payload.x());
                buf.writeDouble(payload.y());
                buf.writeDouble(payload.z());
                buf.writeFloat(payload.yaw());
                buf.writeFloat(payload.pitch());
            },
            buf -> new PositionRestoreRequestPayload(buf.readString(256), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readFloat(), buf.readFloat()));

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
