package dev.packetshield;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.BlockItem;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.Registries;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import dev.packetshield.mixin.ClientWorldAccessor;
import dev.packetshield.mixin.WorldPropertiesAccessor;
import dev.packetshield.mixin.ConnectScreenInvoker;
import dev.packetshield.mixin.ScreenAccessor;
import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
public final class PacketShieldClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("PacketShield");
    private static ServerInfo targetServer;
    private static double savedX, savedY, savedZ;
    private static float savedYaw, savedPitch;
    private static String savedDimension = "minecraft:overworld";
    private static boolean hasSavedPosition, reconnecting;
    private static boolean positionRestoreAllowedForSession;
    private static boolean serverSupportsPositionRestore;
    private static boolean serverRestorePermissionReceived;
    private static boolean serverAllowsPositionRestore;
    private static boolean serverAllowsFallbackSync;
    private static int serverRestorePermissionWaitTicks = -1;
    private static boolean fallbackSyncRecording;
    private static boolean fallbackSyncFrozen;
    private static boolean fallbackSyncPending;
    private static String fallbackSyncDimension;
    private static final Map<Long, FallbackChangesPayload.BlockChange> fallbackBlockChanges = new LinkedHashMap<>();
    private static final Set<UUID> fallbackPickedUpItems = new LinkedHashSet<>();
    private static List<ItemStack> fallbackInventoryBaseline = List.of();
    private static List<FallbackChangesPayload.SlotChange> fallbackSlotChanges = List.of();
    private static final Map<Integer, ItemStack> fallbackForcedSlotChanges = new LinkedHashMap<>();
    private static int fallbackSelectedSlot = -1;
    private static int fallbackSelectedSlotBaseline = -1;
    private static boolean reconnectAutomaticallyForSession;
    private static boolean serverTransferInProgress;
    private static long transferScreenSuppressUntil;
    private static long nextAttemptAt;
    private static int attempts;
    private static boolean attemptInProgress;
    private static long attemptTimeoutAt;
    private static int restorePositionDelayTicks = -1;
    private static int restorePositionTicksRemaining;
    private static boolean restorePositionActive;
    private static ClientWorld cachedWorld;
    private static ClientPlayNetworkHandler stalePlayHandler;
    private static boolean suppressNextStaleDisconnect;
    private static KeyBinding openSettingsKey;
    private static ConnectScreen activeConnector;
    @Override public void onInitializeClient() {
        PacketShieldConfig.load();
        PacketShieldPayloads.registerTypes();
        ClientPlayNetworking.registerGlobalReceiver(PositionRestoreStatusPayload.ID, (payload, context) -> {
            serverRestorePermissionReceived = true;
            serverSupportsPositionRestore = true;
            serverAllowsPositionRestore = payload.positionRestoreAllowed();
            serverAllowsFallbackSync = payload.fallbackSyncAllowed();
            // Some servers finish advertising C2S channels just after JOIN. If the
            // permission reply arrives while the legacy local restore is queued,
            // switch to the server-authoritative path before it starts.
            if (serverRestorePermissionWaitTicks >= 0) restorePositionDelayTicks = -1;
        });
        openSettingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.packetshield.open_settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_P, "category.packetshield"));
        ClientTickEvents.END_CLIENT_TICK.register(PacketShieldClient::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin(client));
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof TitleScreen) {
                ((ScreenAccessor) screen).packetshield$addDrawableChild(ButtonWidget.builder(
                                Text.literal("PacketShield").formatted(Formatting.GREEN),
                                button -> client.setScreen(PacketShieldSettingsScreen.create(screen)))
                        .dimensions(10, 10, 120, 20).build());
            }
        });
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (reconnecting && !serverTransferInProgress && client.player != null && client.world != null) {
                context.drawCenteredTextWithShadow(client.textRenderer,
                        Text.literal(reconnectAutomaticallyForSession
                                ? "You were disconnected, reconnecting..."
                                : "You were disconnected, reconnecting was turned off."),
                        client.getWindow().getScaledWidth() / 2, 8, 0xFFFFFF);
            }
        });
    }
    public static void captureDisconnect(ClientPlayNetworkHandler handler, ServerInfo server, ClientWorld world,
                                         double x, double y, double z, float yaw, float pitch, Text reason) {
        MinecraftClient client = MinecraftClient.getInstance();
        // The closed handler can finish its disconnect callback after the replacement
        // connection has already joined. It must never tear down that new connection.
        if (handler != client.getNetworkHandler()) {
            suppressNextStaleDisconnect = true;
            LOGGER.info("Ignored delayed disconnect callback from an old server connection");
            return;
        }
        if (reconnecting || server == null || isBan(reason.getString())) return;
        targetServer = server;
        cachedWorld = world;
        stalePlayHandler = handler;
        savedX=x; savedY=y; savedZ=z; savedYaw=yaw; savedPitch=pitch;
        savedDimension = world.getRegistryKey().getValue().toString();
        hasSavedPosition=true; reconnecting=true; attempts=0; attemptInProgress=false;
        positionRestoreAllowedForSession = !PacketShieldConfig.doNotAutomaticallyReconnect;
        reconnectAutomaticallyForSession = !PacketShieldConfig.doNotAutomaticallyReconnect;
        serverSupportsPositionRestore = false;
        serverRestorePermissionReceived = false;
        serverAllowsPositionRestore = false;
        serverAllowsFallbackSync = false;
        beginFallbackSyncCapture(world, MinecraftClient.getInstance().player);
        nextAttemptAt=System.currentTimeMillis();
        client.execute(() -> {
            if (reconnecting && client.world != null && client.player != null) client.setScreen(null);
        });
    }

    public static void disconnectOnlyFromServer() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        if (handler == null || client.world == null || client.player == null || handler.getServerInfo() == null) return;

        var player = client.player;
        captureDisconnect(handler, handler.getServerInfo(), client.world,
                player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(),
                Text.literal("Disconnected by PacketShield"));
        reconnectAutomaticallyForSession = false;
        positionRestoreAllowedForSession = false;
        attemptInProgress = false;
        activeConnector = null;
        handler.getConnection().disconnect(Text.literal("Disconnected by PacketShield"));
        client.setScreen(null);
    }

    public static void reconnectFromPauseMenu() {
        if (!reconnecting || targetServer == null || serverTransferInProgress) return;
        reconnectAutomaticallyForSession = true;
        attemptInProgress = false;
        nextAttemptAt = 0;
        MinecraftClient.getInstance().setScreen(null);
    }

    public static boolean shouldShowManualServerButton() {
        return reconnecting && !reconnectAutomaticallyForSession && !serverTransferInProgress
                && targetServer != null && MinecraftClient.getInstance().world != null;
    }

    public static boolean manualReconnectPending() {
        return shouldShowManualServerButton();
    }
    public static void captureServerTransfer(ClientPlayNetworkHandler handler, ClientWorld world) {
        if (world == null) return;
        serverTransferInProgress = true;
        reconnecting = true;
        reconnectAutomaticallyForSession = false;
        targetServer = null;
        cachedWorld = world;
        stalePlayHandler = handler;
        hasSavedPosition = false;
        positionRestoreAllowedForSession = false;
        serverSupportsPositionRestore = false;
        serverRestorePermissionReceived = false;
        serverAllowsPositionRestore = false;
        serverAllowsFallbackSync = false;
        clearFallbackSyncData();
        restorePositionDelayTicks = -1;
        restorePositionActive = false;
    }
    private static boolean isBan(String reason) {
        String s=reason.toLowerCase(java.util.Locale.ROOT);
        return s.contains("ban") || s.contains("blacklist") || s.contains("banned");
    }
    private static void tick(MinecraftClient client) {
        while (openSettingsKey.wasPressed()) {
            client.setScreen(PacketShieldSettingsScreen.create(client.currentScreen));
        }
        if (PacketShieldConfig.doNotAutomaticallyReconnect && positionRestoreAllowedForSession) {
            positionRestoreAllowedForSession = false;
            hasSavedPosition = false;
            restorePositionDelayTicks = -1;
            restorePositionActive = false;
            restorePositionTicksRemaining = 0;
        }
        if (serverRestorePermissionWaitTicks >= 0) {
            if (serverRestorePermissionReceived) {
                if (serverAllowsPositionRestore && positionRestoreAllowedForSession
                        && PacketShieldConfig.positionSyncEnabled && !PacketShieldConfig.doNotAutomaticallyReconnect
                        && hasSavedPosition && client.player != null && client.world != null) {
                    ClientPlayNetworking.send(new PositionRestoreRequestPayload(
                            savedDimension,
                            savedX, savedY, savedZ, savedYaw, savedPitch));
                }
                if (serverAllowsFallbackSync && fallbackSyncPending) sendFallbackChanges();
                finishServerRestoreNegotiation();
            } else if (--serverRestorePermissionWaitTicks == 0) {
                // A modded server did not answer; never fall back to client-side movement
                // when it has advertised the server payload channel.
                finishServerRestoreNegotiation();
            }
        }
        if (restorePositionDelayTicks >= 0 && client.player != null) {
            client.player.setPosition(savedX, savedY, savedZ);
            client.player.setYaw(savedYaw);
            client.player.setPitch(savedPitch);
            if (restorePositionDelayTicks-- == 0) {
                restorePositionDelayTicks = -1;
                restorePositionActive = PacketShieldConfig.positionSyncEnabled
                        && !PacketShieldConfig.doNotAutomaticallyReconnect
                        && positionRestoreAllowedForSession && hasSavedPosition;
                restorePositionTicksRemaining = restorePositionActive ? 600 : 0;
                if (!restorePositionActive) {
                    hasSavedPosition = false;
                    positionRestoreAllowedForSession = false;
                }
            }
        }
        if (restorePositionActive && client.player != null) {
            restorePositionTicksRemaining--;
            double dx = savedX - client.player.getX();
            double dy = savedY - client.player.getY();
            double dz = savedZ - client.player.getZ();
            if (restorePositionTicksRemaining <= 0 || dx * dx + dy * dy + dz * dz <= 0.09) {
                restorePositionActive = false;
                hasSavedPosition = false;
                positionRestoreAllowedForSession = false;
            }
        }
        if (reconnecting && attemptInProgress && activeConnector != null) activeConnector.tick();
        if (!reconnecting || targetServer==null) return;
        if (client.player != null && client.world != null) {
            savedX=client.player.getX(); savedY=client.player.getY(); savedZ=client.player.getZ();
            savedYaw=client.player.getYaw(); savedPitch=client.player.getPitch();
            hasSavedPosition=true;
        }
        if (!reconnectAutomaticallyForSession) return;
        long now = System.currentTimeMillis();
        if (attemptInProgress && now < attemptTimeoutAt) return;
        if (now < nextAttemptAt) return;
        attempts++;
        attemptInProgress=true;
        attemptTimeoutAt=now+35000;
        nextAttemptAt=now+Math.min(30000,3000L*attempts);
        Screen parent=client.currentScreen;
        ConnectScreen connector = createConnector(parent);
        activeConnector = connector;
        ((ConnectScreenInvoker) connector).packetshield$startConnection(client,
                ServerAddress.parse(targetServer.address), targetServer, null);
    }
    private static ConnectScreen createConnector(Screen parent) {
        try {
            var constructor = ConnectScreen.class.getDeclaredConstructor(Screen.class, Text.class);
            constructor.setAccessible(true);
            return constructor.newInstance(parent, Text.translatable("connect.failed"));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not create background connection worker", error);
        }
    }
    public static boolean shouldKeepWorld() { return reconnecting; }
    public static ClientWorld reuseCachedWorld(ClientPlayNetworkHandler handler, ClientWorld.Properties properties,
                                                RegistryKey<World> dimension, RegistryEntry<DimensionType> dimensionType,
                                                int loadDistance, int simulationDistance, Supplier<Profiler> profiler,
                                                WorldRenderer renderer, boolean debug, long seed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (reconnecting) freezeFallbackSyncChanges(client.player);
        // The old player belongs to the closed connection. Clearing it here makes vanilla
        // create a fresh ClientPlayerEntity for the new handler later in onGameJoin.
        if (reconnecting) client.player = null;
        if (!reconnecting || cachedWorld == null || !cachedWorld.getRegistryKey().equals(dimension)) {
            return new ClientWorld(handler, properties, dimension, dimensionType, loadDistance,
                    simulationDistance, profiler, renderer, debug, seed);
        }

        ClientWorld reused = cachedWorld;
        // Avoid forcing Voxy to rebuild its own terrain cache while the server's join
        // packet burst is arriving. It handles a fresh ClientWorld without the hitch.
        if (FabricLoader.getInstance().isModLoaded("voxy")) {
            cachedWorld = null;
            return new ClientWorld(handler, properties, dimension, dimensionType, loadDistance,
                    simulationDistance, profiler, renderer, debug, seed);
        }
        long cachedTime = reused.getLevelProperties().getTime();
        long cachedTimeOfDay = reused.getLevelProperties().getTimeOfDay();
        ((ClientWorldAccessor) reused).packetshield$setNetworkHandler(handler);
        ((ClientWorldAccessor) reused).packetshield$setProperties(properties);
        reused.setTime(cachedTime);
        reused.setTimeOfDay(cachedTimeOfDay);
        java.util.List<Entity> staleEntities = new java.util.ArrayList<>();
        for (Entity entity : reused.getEntities()) {
            if (entity != client.player) staleEntities.add(entity);
        }
        for (Entity entity : staleEntities) {
            reused.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
        }
        if (client.player != null) {
            reused.removeEntity(client.player.getId(), Entity.RemovalReason.DISCARDED);
        }
        reused.setSimulationDistance(simulationDistance);
        cachedWorld = null;
        return reused;
    }
    public static boolean shouldSkipStalePlayHandler(ClientPlayNetworkHandler handler) {
        return stalePlayHandler == handler && handler != MinecraftClient.getInstance().getNetworkHandler();
    }

    public static boolean consumeStaleDisconnectSuppression() {
        boolean suppress = suppressNextStaleDisconnect;
        suppressNextStaleDisconnect = false;
        return suppress;
    }

    public static boolean isUsingFallbackWorld(ClientWorld world) {
        return reconnecting && fallbackSyncRecording && world == cachedWorld;
    }
    public static void onReconnectAttemptFailed() {
        if (!reconnecting || !attemptInProgress) return;
        attemptInProgress=false;
        activeConnector=null;
        nextAttemptAt=System.currentTimeMillis()+Math.min(30000,3000L*Math.max(1,attempts));
    }
    public static boolean shouldSuppressReconnectScreen(Screen screen) {
        if (serverTransferInProgress || System.currentTimeMillis() < transferScreenSuppressUntil) {
            return screen == null || screen instanceof ConnectScreen || screen instanceof DisconnectedScreen
                    || screen instanceof DownloadingTerrainScreen || screen instanceof TitleScreen;
        }
        if (!reconnecting) return false;
        if (!reconnectAutomaticallyForSession) return screen instanceof DisconnectedScreen;
        if (screen == null) return MinecraftClient.getInstance().world == null;
        return screen instanceof ConnectScreen || screen instanceof DisconnectedScreen ||
                screen instanceof DownloadingTerrainScreen || screen instanceof TitleScreen;
    }
    private static void onJoin(MinecraftClient client) {
        if (!reconnecting) return;
        reconnecting=false; attempts=0; attemptInProgress=false; activeConnector=null;
        reconnectAutomaticallyForSession = false;
        if (serverTransferInProgress) transferScreenSuppressUntil = System.currentTimeMillis() + 2000;
        serverTransferInProgress = false;
        if (client.world != null) {
            client.world.getTickManager().setTickRate(20.0F);
            client.world.getTickManager().setFrozen(false);
        }
        if (client.player != null) {
            client.player.setVelocity(0.0, 0.0, 0.0);
            client.player.setSprinting(false);
            client.player.clearStatusEffects();
        }
        serverSupportsPositionRestore = serverSupportsPositionRestore
                || ClientPlayNetworking.canSend(PositionRestoreRequestPayload.ID);
        boolean restoreRequested = PacketShieldConfig.positionSyncEnabled
                && !PacketShieldConfig.doNotAutomaticallyReconnect
                && positionRestoreAllowedForSession && hasSavedPosition && client.player != null;
        boolean fallbackSyncRequested = fallbackSyncPending;
        // Wait for the server's explicit permission payload even if Fabric's channel
        // capability snapshot was not ready on the JOIN tick.
        serverRestorePermissionWaitTicks = restoreRequested || fallbackSyncRequested ? 100 : -1;
        restorePositionDelayTicks = !serverSupportsPositionRestore && restoreRequested ? 10 : -1;
        restorePositionActive = false;
        restorePositionTicksRemaining = 0;
        if (restorePositionDelayTicks < 0 && serverRestorePermissionWaitTicks < 0) {
            hasSavedPosition = false;
            positionRestoreAllowedForSession = false;
        }
    }

    private static void finishServerRestoreNegotiation() {
        serverRestorePermissionWaitTicks = -1;
        hasSavedPosition = false;
        positionRestoreAllowedForSession = false;
        clearFallbackSyncData();
    }

    private static void beginFallbackSyncCapture(ClientWorld world, net.minecraft.client.network.ClientPlayerEntity player) {
        clearFallbackSyncData();
        if (world == null || player == null) return;
        fallbackSyncDimension = world.getRegistryKey().getValue().toString();
        fallbackSyncRecording = true;
        fallbackSyncPending = true;
        fallbackSelectedSlotBaseline = player.getInventory().selectedSlot;
        List<ItemStack> baseline = new ArrayList<>(player.getInventory().size());
        for (int i = 0; i < player.getInventory().size(); i++) baseline.add(player.getInventory().getStack(i).copy());
        fallbackInventoryBaseline = baseline;
    }

    private static void freezeFallbackSyncChanges(net.minecraft.client.network.ClientPlayerEntity player) {
        if (!fallbackSyncRecording || fallbackSyncFrozen) return;
        fallbackSyncRecording = false;
        fallbackSyncFrozen = true;
        if (player == null) return;
        Map<Integer, ItemStack> changedSlots = new LinkedHashMap<>();
        int size = Math.min(fallbackInventoryBaseline.size(), player.getInventory().size());
        for (int i = 0; i < size; i++) {
            ItemStack current = player.getInventory().getStack(i);
            if (!ItemStack.areEqual(fallbackInventoryBaseline.get(i), current)) {
                changedSlots.put(i, current.copy());
            }
        }
        fallbackForcedSlotChanges.forEach((slot, stack) -> changedSlots.put(slot, stack.copy()));
        fallbackSlotChanges = changedSlots.entrySet().stream()
                .map(entry -> new FallbackChangesPayload.SlotChange(entry.getKey(), entry.getValue()))
                .toList();
        fallbackSelectedSlot = player.getInventory().selectedSlot != fallbackSelectedSlotBaseline
                ? player.getInventory().selectedSlot : -1;
        fallbackInventoryBaseline = List.of();
    }

    public static void recordFallbackBlockChange(ClientWorld world, BlockPos pos, net.minecraft.block.BlockState state) {
        if (!fallbackSyncRecording || world != cachedWorld) return;
        saveFallbackBlockChange(pos, state);
    }

    public static void recordFallbackPlacement(ClientWorld world, BlockPos pos, BlockItem item) {
        if (!fallbackSyncRecording || world != cachedWorld) return;
        net.minecraft.block.BlockState visibleState = world.getBlockState(pos);
        // Client block placement is normally server-authoritative. While disconnected,
        // the interaction can succeed locally without a block-state packet ever arriving.
        // Record the placed block item as a fallback when the local world stayed air.
        saveFallbackBlockChange(pos, visibleState.isAir() || visibleState.isReplaceable()
                ? item.getBlock().getDefaultState() : visibleState);
    }

    public static void retainFinalFallbackPlacementItem(ClientPlayerEntity player, Hand hand, ItemStack original) {
        if (!fallbackSyncRecording || player.getWorld() != cachedWorld) return;
        int slot = hand == Hand.MAIN_HAND ? player.getInventory().selectedSlot : player.getInventory().size() - 1;
        fallbackForcedSlotChanges.put(slot, ItemStack.EMPTY);
        player.setStackInHand(hand, original);
        LOGGER.info("Deferred consumption of final fallback placement item in inventory slot {}", slot);
    }

    private static void saveFallbackBlockChange(BlockPos pos, net.minecraft.block.BlockState state) {
        String properties = state.getEntries().entrySet().stream()
                .map(entry -> entry.getKey().getName() + "=" + entry.getValue())
                .sorted().collect(java.util.stream.Collectors.joining(","));
        FallbackChangesPayload.BlockChange change = new FallbackChangesPayload.BlockChange(
                pos.getX(), pos.getY(), pos.getZ(), Registries.BLOCK.getId(state.getBlock()).toString(), properties);
        fallbackBlockChanges.put(pos.asLong(), change);
        LOGGER.info("Recorded fallback block placement {} at {} {} {}",
                change.blockId(), change.x(), change.y(), change.z());
    }

    public static void recordFallbackEntityRemoval(ClientWorld world, int entityId) {
        if (!fallbackSyncRecording || world != cachedWorld) return;
        Entity entity = world.getEntityById(entityId);
        if (entity instanceof ItemEntity) fallbackPickedUpItems.add(entity.getUuid());
    }

    private static void sendFallbackChanges() {
        if (fallbackSyncDimension == null || !fallbackSyncPending) return;
        if (!fallbackSyncFrozen) {
            // Block and pickup records are independent of the inventory snapshot. Do
            // not silently discard them if a mod changes the onGameJoin world setup
            // and the cached-world hook did not freeze the old player first.
            fallbackSyncRecording = false;
            fallbackSyncFrozen = true;
            fallbackSlotChanges = fallbackForcedSlotChanges.entrySet().stream()
                    .map(entry -> new FallbackChangesPayload.SlotChange(entry.getKey(), entry.getValue().copy()))
                    .toList();
            fallbackSelectedSlot = -1;
            LOGGER.warn("Fallback snapshot was not frozen before join; sending recorded block/item changes only");
        }
        List<FallbackChangesPayload.BlockChange> blocks = new ArrayList<>(fallbackBlockChanges.values());
        LOGGER.info("Sending fallback sync: {} block changes, {} inventory slots, {} collected items",
                blocks.size(), fallbackSlotChanges.size(), fallbackPickedUpItems.size());
        boolean sentAny = false;
        int selected = fallbackSelectedSlot;
        List<FallbackChangesPayload.SlotChange> slots = fallbackSlotChanges;
        List<UUID> pickedUpItems = new ArrayList<>(fallbackPickedUpItems);
        int firstItemEnd = Math.min(FallbackChangesPayload.MAX_ITEMS, pickedUpItems.size());
        if (blocks.isEmpty()) {
            if (!slots.isEmpty() || !pickedUpItems.isEmpty() || selected >= 0) {
                ClientPlayNetworking.send(new FallbackChangesPayload(fallbackSyncDimension, List.of(), slots,
                        pickedUpItems.subList(0, firstItemEnd), selected));
                sentAny = true;
            }
        } else {
            for (int start = 0; start < blocks.size(); start += FallbackChangesPayload.MAX_BLOCKS) {
                int end = Math.min(start + FallbackChangesPayload.MAX_BLOCKS, blocks.size());
                List<FallbackChangesPayload.BlockChange> blockBatch = blocks.subList(start, end);
                ClientPlayNetworking.send(new FallbackChangesPayload(fallbackSyncDimension, blockBatch,
                        start == 0 ? slots : List.of(),
                        start == 0 ? pickedUpItems.subList(0, firstItemEnd) : List.of(),
                        start == 0 ? selected : -1));
                sentAny = true;
            }
        }
        for (int start = firstItemEnd; start < pickedUpItems.size(); start += FallbackChangesPayload.MAX_ITEMS) {
            int end = Math.min(start + FallbackChangesPayload.MAX_ITEMS, pickedUpItems.size());
            ClientPlayNetworking.send(new FallbackChangesPayload(fallbackSyncDimension, List.of(), List.of(),
                    pickedUpItems.subList(start, end), -1));
            sentAny = true;
        }
        if (sentAny) fallbackSyncPending = false;
    }

    private static void clearFallbackSyncData() {
        fallbackSyncRecording = false;
        fallbackSyncFrozen = false;
        fallbackSyncPending = false;
        fallbackSyncDimension = null;
        fallbackBlockChanges.clear();
        fallbackPickedUpItems.clear();
        fallbackInventoryBaseline = List.of();
        fallbackSlotChanges = List.of();
        fallbackForcedSlotChanges.clear();
        fallbackSelectedSlot = -1;
        fallbackSelectedSlotBaseline = -1;
    }

    public static boolean shouldAllowManualLeave(Screen screen) {
        return reconnecting && !reconnectAutomaticallyForSession && !serverTransferInProgress
                && screen instanceof TitleScreen;
    }

    public static boolean shouldPreserveWorldForTransfer(boolean transferring) {
        return transferring && serverTransferInProgress;
    }

    public static void onManualLeave() {
        reconnecting = false;
        serverTransferInProgress = false;
        transferScreenSuppressUntil = 0;
        reconnectAutomaticallyForSession = false;
        attemptInProgress = false;
        attempts = 0;
        activeConnector = null;
        stalePlayHandler = null;
        cachedWorld = null;
        hasSavedPosition = false;
        positionRestoreAllowedForSession = false;
        clearFallbackSyncData();
        restorePositionDelayTicks = -1;
        restorePositionActive = false;
        restorePositionTicksRemaining = 0;
    }

    public static void onServerPositionCorrection() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!PacketShieldConfig.positionSyncEnabled || PacketShieldConfig.doNotAutomaticallyReconnect
                || !positionRestoreAllowedForSession || client.player == null) return;
        if (restorePositionDelayTicks >= 0) {
            client.player.setPosition(savedX, savedY, savedZ);
            client.player.setYaw(savedYaw);
            client.player.setPitch(savedPitch);
            return;
        }
        if (!restorePositionActive) return;

        double dx = savedX - client.player.getX();
        double dy = savedY - client.player.getY();
        double dz = savedZ - client.player.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= 0.3) {
            restorePositionActive = false;
            hasSavedPosition = false;
            return;
        }

        double step = Math.min(0.3, distance);
        client.player.setPosition(
                client.player.getX() + dx / distance * step,
                client.player.getY() + dy / distance * step,
                client.player.getZ() + dz / distance * step);
    }

    public static boolean isHoldingPositionForRestore() {
        return PacketShieldConfig.positionSyncEnabled && !PacketShieldConfig.doNotAutomaticallyReconnect
                && positionRestoreAllowedForSession && restorePositionDelayTicks >= 0;
    }
}
