package dev.packetshield;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class PacketShieldSettingsScreen {
    private PacketShieldSettingsScreen() {}

    public static Screen create(Screen parent) {
        return new Screen(Text.literal("PacketShield Settings")) {
            private ButtonWidget teleportButton;

            @Override protected void init() {
                teleportButton = addDrawableChild(ButtonWidget.builder(teleportLabel(), button -> {
                    PacketShieldConfig.positionSyncEnabled = !PacketShieldConfig.positionSyncEnabled;
                    PacketShieldConfig.save();
                    button.setMessage(teleportLabel());
                }).dimensions(width / 2 - 190, height / 2 - 35, 380, 20).build());
                teleportButton.active = !PacketShieldConfig.doNotAutomaticallyReconnect;
                addDrawableChild(ButtonWidget.builder(reconnectLabel(), button -> {
                    PacketShieldConfig.doNotAutomaticallyReconnect = !PacketShieldConfig.doNotAutomaticallyReconnect;
                    PacketShieldConfig.save();
                    button.setMessage(reconnectLabel());
                    teleportButton.active = !PacketShieldConfig.doNotAutomaticallyReconnect;
                    teleportButton.setMessage(teleportLabel());
                }).dimensions(width / 2 - 190, height / 2 - 5, 380, 20).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
                        .dimensions(width / 2 - 100, height / 2 + 30, 200, 20).build());
            }

            private Text teleportLabel() {
                if (PacketShieldConfig.doNotAutomaticallyReconnect) {
                    return Text.literal("Attempt to teleport to client position on server: DISABLED");
                }
                return Text.literal("Attempt to teleport to client position on server: " +
                        (PacketShieldConfig.positionSyncEnabled ? "ON" : "OFF"));
            }

            private Text reconnectLabel() {
                return Text.literal("Do not automatically reconnect: " +
                        (PacketShieldConfig.doNotAutomaticallyReconnect ? "ON" : "OFF"));
            }

            @Override public void close() { client.setScreen(parent); }
        };
    }
}
