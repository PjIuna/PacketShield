# PacketShield

Client side Fabric mod project for Minecraft 1.21.1 (Java 21).

## Build

Install Java 21 and Gradle 8.10 or newer, then run `gradle build`. The remapped mod jar is written under `build/libs/`.

Install Fabric API alongside the jar. Press **P** in game to open PacketShield Settings. If Mod Menu is installed, its mod config entry opens the same screen.
The main menu also has a green **PacketShield** button that opens the settings.

## Behavior and limits

- Saves the server entry and client position when a disconnect reason does not contain the English words `ban`, `banned`, or `blacklist`.
- Keeps the current client world loaded, leaves the game view unobstructed, and displays **You were disconnected, reconnecting...** at the top while retrying the saved server with increasing delays capped at 30 seconds.
- On reconnect to the same dimension, reuses the cached client world and its loaded chunks. Incoming server chunk packets refresh that cache; a different dimension falls back to a new world.
- For server initiated transfers, keeps the current world and view active while Minecraft connects to the destination, suppresses transfer/loading screens, and clears that suppression after joining.
- Local movement during the outage only changes the retained client copy of the world. The server does not receive that movement, and its state remains authoritative after reconnect.
- When enabled, the client holds the saved local position for 0.5 seconds after reconnect without sending movement packets. It then resumes normal movement updates; if the server corrects it, the client advances toward the saved point in 0.3-block steps for up to 30 seconds.
- **Do not automatically reconnect** is off by default. When on, the fallback world remains open, the HUD says **You were disconnected, reconnecting was turned off.**, and the player can leave manually through the game menu.
- Turning **Do not automatically reconnect** on greys out and disables the position restore setting. Position restore is also skipped after a **Client Only** disconnect, even if the player later reconnects manually; it only applies to automatic reconnects after a disconnect.
- Install PacketShield on a Fabric server to enable server-authorized restoration. It is on by default on first install. An operator can run `/allowpositionrestore` to toggle it, or `/allowpositionrestore true|false` to set it. Its setting persists in `config/packetshield-server.properties`. When enabled, a reconnecting modded client sends its saved coordinates after joining; the server logs them with the player's name and teleports the player if they are still in the same dimension and within the world border. If the server does not have PacketShield, clients retain the existing local restore behavior.
- Fallback edits are also on by default on first install. Operators can toggle `/allowfallbacksync` or set `/allowfallbacksync true|false`; the setting is stored in the same server config. With the setting enabled on a server that also runs PacketShield, the client sends locally recorded block changes, changed inventory slots, selected hotbar slot, and collected item entity IDs after reconnecting. The server applies those changes in the original dimension.
- Operators are told the current position restore, fallback sync, and alert settings when they join. Use `/packetshieldalerts false` to turn those notices off, `/packetshieldalerts true` to turn them back on, or `/packetshieldalerts` to toggle them. This alert setting defaults to on.
- The pause menu splits its disconnect control into two half-width buttons: vanilla **Disconnect** on the left and green **Client Only** on the right. Hover over the right button for its full action label. Reopen the pause menu after disconnecting and it becomes **Reconnect** (with the full label in its tooltip).
- Reconnect reuses the retained client world and loaded chunks when Voxy is not installed. With Voxy, it uses a fresh client world to avoid a hitch while Voxy updates its terrain cache.
- Ban detection depends on the server's disconnect text. Custom or localized ban messages cannot be classified reliably from the client alone.
