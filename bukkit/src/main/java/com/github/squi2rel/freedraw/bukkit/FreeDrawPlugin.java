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

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
        if (DataHolder.db != null) DataHolder.db.close();
        if (tickTask != -1) getServer().getScheduler().cancelTask(tickTask);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
        LOGGER.info("FreeDraw disabled");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        // The initial CONFIG push can be dropped when it lands before the client
        // finished the login/config phase (slow first joins with VR + shaders, or
        // proxy channel registration still propagating), leaving the client stuck
        // with connected=false for the whole session. The client answers every
        // CONFIG push with its own CONFIG (which registers it in DataHolder.players),
        // so re-push every 60 ticks until it answers, leaves, or we hit the cap.
        // Purely server-side recovery, no client changes required.
        // 60 ticks = 3 seconds per attempt; 60 attempts x 3s + 2s initial delay
        // means we give up after roughly 3 minutes.
        final AtomicInteger attempts = new AtomicInteger();
        final int[] taskId = {-1};
        taskId[0] = getServer().getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline() || !player.isValid()
                    || DataHolder.players.containsKey(uuid)
                    || attempts.incrementAndGet() > 60) {
                getServer().getScheduler().cancelTask(taskId[0]);
                return;
            }
            DataHolder.onPlayerJoin(player);
        }, 40L, 60L).getTaskId();
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
