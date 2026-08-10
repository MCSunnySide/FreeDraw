package com.github.squi2rel.freedraw.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Forwards the {@code freedraw:payload} plugin channel between clients and backend
 * servers. Velocity's default forwarding can drop or mangle plugin messages whose
 * channel it has not seen registered on both ends (Fabric clients announce their
 * channels during the login configuration phase, which Velocity may not track for
 * custom payloads). Explicitly forwarding guarantees byte-for-byte delivery.
 */
@Plugin(
        id = "freedraw-velocity",
        name = "FreeDraw",
        version = "1.1.0",
        description = "Forwards FreeDraw drawing data (freedraw:payload) through the proxy.",
        authors = {"squi2rel"}
)
public class FreeDrawVelocity {

    /** Must match {@code freedraw:payload} used by the Fabric mod and Bukkit plugin. */
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("freedraw", "payload");

    private final ProxyServer proxy;
    private final Logger logger;

    @Inject
    public FreeDrawVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) return;

        ChannelMessageSource source = event.getSource();
        byte[] data = event.getData();
        boolean clientToServer = source instanceof Player;
        String player = clientToServer
                ? ((Player) source).getUsername()
                : event.getTarget() instanceof Player p ? p.getUsername() : "?";

        logger.info("freedraw:payload {} -> {} ({} bytes, hex={})",
                clientToServer ? "client->server" : "server->client",
                player, data != null ? data.length : -1,
                data != null ? hex(data, 32) : "");

        // Ensure the message is forwarded to the other side unchanged.
        event.setResult(PluginMessageEvent.ForwardResult.forward());
    }

    /** First n bytes as hex, for diagnosing proxy truncation/mangling. */
    private static String hex(byte[] bytes, int n) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(n, bytes.length);
        for (int i = 0; i < limit; i++) {
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }
}
