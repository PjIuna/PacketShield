package dev.packetshield;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
public final class PacketShieldConfig {
    public static boolean positionSyncEnabled = true;
    public static boolean doNotAutomaticallyReconnect = false;
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("packetshield.properties");
    public static void load() {
        Properties p = new Properties();
        try {
            if (Files.exists(PATH)) try (var in = Files.newInputStream(PATH)) { p.load(in); }
            positionSyncEnabled = Boolean.parseBoolean(p.getProperty("positionSyncEnabled",
                    p.getProperty("attemptTeleportToClientPosition", "true")));
            doNotAutomaticallyReconnect = Boolean.parseBoolean(p.getProperty("doNotAutomaticallyReconnect", "false"));
        } catch (IOException ignored) {}
    }
    public static void save() {
        Properties p = new Properties();
        p.setProperty("positionSyncEnabled", Boolean.toString(positionSyncEnabled));
        p.setProperty("doNotAutomaticallyReconnect", Boolean.toString(doNotAutomaticallyReconnect));
        try (var out = Files.newOutputStream(PATH)) { p.store(out, "PacketShield settings"); }
        catch (IOException ignored) {}
    }
}
