package dev.packetshield;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
public final class PacketShieldModMenu implements ModMenuApi {
    @Override public ConfigScreenFactory<?> getModConfigScreenFactory() { return PacketShieldModMenu::createScreen; }
    private static Screen createScreen(Screen parent) {
        return PacketShieldSettingsScreen.create(parent);
    }
}
