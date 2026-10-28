package dev.packetshield;

import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record FallbackChangesPayload(String dimension, List<BlockChange> blocks, List<SlotChange> slots,
                                    List<UUID> pickedUpItems, int selectedSlot) implements CustomPayload {
    public static final int MAX_BLOCKS = 128;
    public static final int MAX_SLOTS = 64;
    public static final int MAX_ITEMS = 128;
    public static final Id<FallbackChangesPayload> ID =
            new Id<>(Identifier.of("packetshield", "fallback_changes"));
    public static final PacketCodec<RegistryByteBuf, FallbackChangesPayload> CODEC = PacketCodec.ofStatic(
            (buf, payload) -> {
                buf.writeString(payload.dimension(), 256);
                buf.writeVarInt(payload.blocks().size());
                for (BlockChange block : payload.blocks()) {
                    buf.writeInt(block.x());
                    buf.writeInt(block.y());
                    buf.writeInt(block.z());
                    buf.writeString(block.blockId(), 256);
                    buf.writeString(block.properties(), 2048);
                }
                buf.writeVarInt(payload.slots().size());
                for (SlotChange slot : payload.slots()) {
                    buf.writeVarInt(slot.slot());
                    ItemStack.PACKET_CODEC.encode(buf, slot.stack());
                }
                buf.writeVarInt(payload.pickedUpItems().size());
                for (UUID uuid : payload.pickedUpItems()) {
                    buf.writeUuid(uuid);
                }
                buf.writeVarInt(payload.selectedSlot());
            },
            buf -> {
                String dimension = buf.readString(256);
                int blockCount = checkedCount(buf.readVarInt(), MAX_BLOCKS, "block changes");
                List<BlockChange> blocks = new ArrayList<>(blockCount);
                for (int i = 0; i < blockCount; i++) {
                    blocks.add(new BlockChange(buf.readInt(), buf.readInt(), buf.readInt(),
                            buf.readString(256), buf.readString(2048)));
                }
                int slotCount = checkedCount(buf.readVarInt(), MAX_SLOTS, "inventory slots");
                List<SlotChange> slots = new ArrayList<>(slotCount);
                for (int i = 0; i < slotCount; i++) {
                    slots.add(new SlotChange(buf.readVarInt(), ItemStack.PACKET_CODEC.decode(buf)));
                }
                int itemCount = checkedCount(buf.readVarInt(), MAX_ITEMS, "picked up items");
                List<UUID> items = new ArrayList<>(itemCount);
                for (int i = 0; i < itemCount; i++) items.add(buf.readUuid());
                return new FallbackChangesPayload(dimension, blocks, slots, items, buf.readVarInt());
            });

    private static int checkedCount(int count, int max, String description) {
        if (count < 0 || count > max) throw new IllegalArgumentException("Invalid " + description + " count: " + count);
        return count;
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public record BlockChange(int x, int y, int z, String blockId, String properties) {}
    public record SlotChange(int slot, ItemStack stack) {}
}
