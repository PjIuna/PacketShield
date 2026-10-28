package dev.packetshield;

import com.mojang.brigadier.arguments.BoolArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class PacketShieldServer implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("PacketShield");
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("packetshield-server.properties");
    private static volatile boolean positionRestoreAllowed;
    private static volatile boolean fallbackSyncAllowed;
    private static volatile boolean operatorJoinAlerts = true;
    private static final Map<UUID, Map<Integer, ItemStack>> blockItemReserves = new HashMap<>();

    @Override
    public void onInitialize() {
        PacketShieldPayloads.registerTypes();
        loadConfig();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            if (ServerPlayNetworking.canSend(player, PositionRestoreStatusPayload.ID)) {
                ServerPlayNetworking.send(player,
                        new PositionRestoreStatusPayload(positionRestoreAllowed, fallbackSyncAllowed));
            }
            if (operatorJoinAlerts && server.getPlayerManager().isOperator(player.getGameProfile())) {
                player.sendMessage(Text.literal("PacketShield toggles — Position restore: "
                        + onOff(positionRestoreAllowed) + " | Fallback sync: " + onOff(fallbackSyncAllowed)
                        + " | Join alerts: ON. Use /packetshieldalerts false to hide these notices."), false);
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(PositionRestoreRequestPayload.ID,
                (payload, context) -> handleRestoreRequest(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(FallbackChangesPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            LOGGER.info("Received fallback sync from {}: {} blocks, {} slots, {} items; enabled={}; payload dimension={}",
                    player.getGameProfile().getName(), payload.blocks().size(), payload.slots().size(),
                    payload.pickedUpItems().size(), fallbackSyncAllowed, payload.dimension());
            applyFallbackChanges(player, payload);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                maintainBlockItemReserves(player);
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("allowpositionrestore")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(context -> setAllowed(!positionRestoreAllowed, context.getSource()))
                        .then(argument("enabled", BoolArgumentType.bool())
                                .executes(context -> setAllowed(
                                        BoolArgumentType.getBool(context, "enabled"), context.getSource()))));
            dispatcher.register(literal("allowfallbacksync")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(context -> setFallbackSyncAllowed(!fallbackSyncAllowed, context.getSource()))
                        .then(argument("enabled", BoolArgumentType.bool())
                                .executes(context -> setFallbackSyncAllowed(
                                        BoolArgumentType.getBool(context, "enabled"), context.getSource()))));
            dispatcher.register(literal("packetshieldalerts")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(context -> setOperatorJoinAlerts(!operatorJoinAlerts, context.getSource()))
                    .then(argument("enabled", BoolArgumentType.bool())
                            .executes(context -> setOperatorJoinAlerts(
                                    BoolArgumentType.getBool(context, "enabled"), context.getSource()))));
        });
    }

    private static int setAllowed(boolean allowed, net.minecraft.server.command.ServerCommandSource source) {
        positionRestoreAllowed = allowed;
        saveConfig();
        source.sendFeedback(() -> Text.literal("PacketShield position restore is "
                + (allowed ? "allowed." : "disabled.")), true);
        LOGGER.info("Position restore permission set to {} by {}", allowed, source.getName());
        return 1;
    }

    private static int setFallbackSyncAllowed(boolean allowed, net.minecraft.server.command.ServerCommandSource source) {
        fallbackSyncAllowed = allowed;
        saveConfig();
        source.sendFeedback(() -> Text.literal("PacketShield fallback world sync is "
                + (allowed ? "allowed." : "disabled.")), true);
        LOGGER.info("Fallback world sync permission set to {} by {}", allowed, source.getName());
        return 1;
    }

    private static int setOperatorJoinAlerts(boolean enabled, net.minecraft.server.command.ServerCommandSource source) {
        operatorJoinAlerts = enabled;
        saveConfig();
        source.sendFeedback(() -> Text.literal("PacketShield operator join alerts are "
                + (enabled ? "enabled." : "disabled.")), true);
        LOGGER.info("Operator join alerts set to {} by {}", enabled, source.getName());
        return 1;
    }

    private static String onOff(boolean enabled) {
        return enabled ? "ON" : "OFF";
    }

    private static void maintainBlockItemReserves(ServerPlayerEntity player) {
        Map<Integer, ItemStack> reserves = blockItemReserves.computeIfAbsent(player.getUuid(), ignored -> new HashMap<>());
        boolean changed = false;
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            ItemStack reserve = reserves.get(slot);
            if (reserve != null) {
                ItemStack comparison = stack.copy();
                comparison.setCount(1);
                if (stack.isEmpty() || !ItemStack.areEqual(reserve, comparison)) {
                    reserves.remove(slot);
                } else if (stack.getCount() == 1) {
                    // The tracked two-block stack was used or partially dropped.
                    // Remove the remaining reserve so it still represents one block.
                    player.getInventory().setStack(slot, ItemStack.EMPTY);
                    reserves.remove(slot);
                    changed = true;
                } else if (stack.getCount() != 2) {
                    reserves.remove(slot);
                }
                continue;
            }

            if (stack.getCount() == 1 && stack.getMaxCount() > 1 && stack.getItem() instanceof net.minecraft.item.BlockItem) {
                ItemStack marker = stack.copy();
                marker.setCount(1);
                stack.increment(1);
                reserves.put(slot, marker);
                changed = true;
            }
        }
        if (reserves.isEmpty()) blockItemReserves.remove(player.getUuid());
        if (changed) {
            player.playerScreenHandler.sendContentUpdates();
            if (player.currentScreenHandler != player.playerScreenHandler) {
                player.currentScreenHandler.sendContentUpdates();
            }
        }
    }

    private static void applyFallbackChanges(ServerPlayerEntity player, FallbackChangesPayload payload) {
        if (!fallbackSyncAllowed) return;
        if (!player.getWorld().getRegistryKey().getValue().toString().equals(payload.dimension())) return;
        var world = player.getServerWorld();
        int appliedBlocks = 0;

        for (FallbackChangesPayload.BlockChange change : payload.blocks()) {
            if (change.y() < world.getBottomY() || change.y() >= world.getTopY()) continue;
            BlockPos pos = new BlockPos(change.x(), change.y(), change.z());
            if (!world.getWorldBorder().contains(pos)) continue;
            BlockState state = decodeBlockState(change);
            if (state == null) continue;
            BlockState existing = world.getBlockState(pos);
            // A fallback placement is only allowed to fill an empty/replaceable spot.
            // This prevents stale client state from overwriting a block another player
            // placed while this client was reconnecting.
            if (!state.isAir() && !existing.isReplaceable() && !existing.equals(state)) continue;
            if (world.setBlockState(pos, state, Block.NOTIFY_ALL)) appliedBlocks++;
        }

        for (FallbackChangesPayload.SlotChange change : payload.slots()) {
            if (change.slot() < 0 || change.slot() >= player.getInventory().size()) continue;
            ItemStack stack = change.stack();
            player.getInventory().setStack(change.slot(), stack.copy());
        }
        if (payload.selectedSlot() >= 0 && payload.selectedSlot() < 9) {
            player.getInventory().selectedSlot = payload.selectedSlot();
        }

        for (var uuid : payload.pickedUpItems()) {
            var entity = world.getEntity(uuid);
            if (entity instanceof ItemEntity itemEntity) itemEntity.discard();
        }
        if (!payload.slots().isEmpty() || payload.selectedSlot() >= 0) {
            player.playerScreenHandler.sendContentUpdates();
            if (player.currentScreenHandler != player.playerScreenHandler) {
                player.currentScreenHandler.sendContentUpdates();
            }
        }
        if (!payload.blocks().isEmpty()) {
            LOGGER.info("Fallback sync from {}: applied {}/{} block changes",
                    player.getGameProfile().getName(), appliedBlocks, payload.blocks().size());
        }
    }

    private static BlockState decodeBlockState(FallbackChangesPayload.BlockChange change) {
        Identifier id = Identifier.tryParse(change.blockId());
        if (id == null) return null;
        Block block = Registries.BLOCK.getOrEmpty(id).orElse(null);
        if (block == null) return null;
        BlockState state = block.getDefaultState();
        if (change.properties().isBlank()) return state;
        for (String encoded : change.properties().split(",")) {
            int separator = encoded.indexOf('=');
            if (separator <= 0) continue;
            String name = encoded.substring(0, separator);
            String value = encoded.substring(separator + 1);
            Property<?> property = block.getStateManager().getProperty(name);
            if (property != null) state = withProperty(state, property, value);
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<T> property,
                                                                       String value) {
        var parsed = property.parse(value);
        return parsed.map(t -> state.with(property, t)).orElse(state);
    }

    private static void handleRestoreRequest(ServerPlayerEntity player, PositionRestoreRequestPayload payload) {
        if (!positionRestoreAllowed) return;
        if (!player.getWorld().getRegistryKey().getValue().toString().equals(payload.dimension())) return;
        if (!Double.isFinite(payload.x()) || !Double.isFinite(payload.y()) || !Double.isFinite(payload.z())
                || !Float.isFinite(payload.yaw()) || !Float.isFinite(payload.pitch())) return;

        var world = player.getServerWorld();
        if (payload.y() < world.getBottomY() || payload.y() >= world.getTopY()) return;
        if (!world.getWorldBorder().contains(new Vec3d(payload.x(), payload.y(), payload.z()))) return;

        LOGGER.info("Position restore request from {} ({}) in {}: x={}, y={}, z={}, yaw={}, pitch={}",
                player.getGameProfile().getName(), player.getUuid(), payload.dimension(), payload.x(),
                payload.y(), payload.z(), payload.yaw(), payload.pitch());
        player.teleport(world, payload.x(), payload.y(), payload.z(), payload.yaw(), payload.pitch());
    }

    private static void loadConfig() {
        Properties properties = new Properties();
        try {
            if (Files.exists(CONFIG_PATH)) {
                try (var input = Files.newInputStream(CONFIG_PATH)) {
                    properties.load(input);
                }
            }
            positionRestoreAllowed = Boolean.parseBoolean(properties.getProperty("allowPositionRestore", "true"));
            fallbackSyncAllowed = Boolean.parseBoolean(properties.getProperty("allowFallbackSync", "true"));
            operatorJoinAlerts = Boolean.parseBoolean(properties.getProperty("operatorJoinAlerts", "true"));
        } catch (IOException error) {
            LOGGER.warn("Could not read PacketShield server config", error);
        }
    }

    private static void saveConfig() {
        Properties properties = new Properties();
        properties.setProperty("allowPositionRestore", Boolean.toString(positionRestoreAllowed));
        properties.setProperty("allowFallbackSync", Boolean.toString(fallbackSyncAllowed));
        properties.setProperty("operatorJoinAlerts", Boolean.toString(operatorJoinAlerts));
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (var output = Files.newOutputStream(CONFIG_PATH)) {
                properties.store(output, "PacketShield server settings");
            }
        } catch (IOException error) {
            LOGGER.warn("Could not save PacketShield server config", error);
        }
    }
}
