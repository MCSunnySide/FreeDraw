package com.github.squi2rel.freedraw.bukkit;

import com.github.squi2rel.freedraw.bukkit.network.PacketBuf;
import com.github.squi2rel.freedraw.bukkit.network.PacketHandler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.logging.Logger;

/**
 * Bukkit/Paper server-side implementation of the FreeDraw Fabric mod.
 *
 * Clients that run the FreeDraw Fabric mod send their drawing data over the
 * {@code freedraw:payload} plugin channel. This plugin validates, stores and
 * rebroadcasts that data so drawing works on non-Fabric servers.
 */
public class FreeDrawPlugin extends JavaPlugin implements Listener, PluginMessageListener {

    /** Must match {@code DrawPayload.CONFIG_PAYLOAD_ID} in the Fabric mod (freedraw:payload). */
    public static final String CHANNEL = "freedraw:payload";

    public static FreeDrawPlugin instance;
    public static Logger LOGGER;
    /** Version sent to clients in the CONFIG packet; must share the client's major.minor. */
    public static String version;
    /** Fallback if plugin.yml version is missing/invalid (e.g. unexpanded ${version} template). */
    public static final String FALLBACK_VERSION = "1.1.0";

    private int tickTask = -1;

    @Override
    public void onEnable() {
        instance = this;
        LOGGER = getLogger();
        version = getDescription().getVersion();
        if (version == null || version.isBlank() || version.contains("${")) {
            LOGGER.warning("plugin.yml version missing or invalid (got '" + version + "'), using fallback " + FALLBACK_VERSION);
            version = FALLBACK_VERSION;
        }

        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);

        DataHolder.load();

        CommandHandler commandHandler = CommandHandler.INSTANCE;
        getCommand("drawcolor").setExecutor(commandHandler);
        getCommand("drawcolor").setTabCompleter(commandHandler);
        getCommand("freedraw").setExecutor(commandHandler);
        getCommand("freedraw").setTabCompleter(commandHandler);

        getServer().getPluginManager().registerEvents(this, this);

        tickTask = getServer().getScheduler().runTaskTimer(this, DataHolder::update, 1L, 1L).getTaskId();

        LOGGER.info("FreeDraw " + version + " enabled. Channel: " + CHANNEL);
    }

    @Override
    public void onDisable() {
        DataHolder.save();
        if (tickTask != -1) getServer().getScheduler().cancelTask(tickTask);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
        LOGGER.info("FreeDraw disabled");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Delay the CONFIG send: on proxied networks (Velocity/BungeeCord) the player first joins
        // a lobby and is then transferred to this backend. Sending plugin-message data immediately
        // on join can arrive before the proxy finished propagating the channel registration, which
        // truncates/corrupts the S2C packet. Waiting a few ticks lets the proxy settle.
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && player.isValid()) {
                DataHolder.onPlayerJoin(player);
            }
        }, 40L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        DataHolder.onPlayerLeave(event.getPlayer());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) return;
        try {
            PacketHandler.handle(player, PacketBuf.wrapped(message));
        } catch (Exception e) {
            player.kickPlayer("FreeDraw protocol error: " + e);
        }
    }
}
