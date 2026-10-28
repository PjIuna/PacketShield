package dev.packetshield.mixin;

import dev.packetshield.PacketShieldClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin {
    @Shadow private ButtonWidget exitButton;

    @Inject(method = "initWidgets", at = @At("TAIL"))
    private void packetshield$splitDisconnectButton(CallbackInfo ci) {
        if (exitButton == null) return;
        int originalWidth = exitButton.getWidth();
        int buttonWidth = (originalWidth - 4) / 2;
        int center = ((net.minecraft.client.gui.screen.Screen) (Object) this).width / 2;
        int left = center - (buttonWidth * 2 + 4) / 2;
        exitButton.setWidth(buttonWidth);
        exitButton.setX(left);

        boolean reconnectPending = PacketShieldClient.manualReconnectPending();
        Text tooltip = Text.literal(reconnectPending ? "Reconnect To Server" : "Disconnect Only From Server");
        Text label = Text.literal(reconnectPending ? "Reconnect" : "Client Only")
                .formatted(Formatting.GREEN);
        ButtonWidget serverButton = ButtonWidget.builder(label, button -> {
                    if (PacketShieldClient.manualReconnectPending()) {
                        PacketShieldClient.reconnectFromPauseMenu();
                    } else {
                        PacketShieldClient.disconnectOnlyFromServer();
                    }
                })
                .dimensions(left + buttonWidth + 4, exitButton.getY(), buttonWidth, exitButton.getHeight())
                .tooltip(Tooltip.of(tooltip))
                .build();
        ((ScreenAccessor) this).packetshield$addDrawableChild(serverButton);
    }
}
