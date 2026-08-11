package com.github.squi2rel.freedraw.bukkit;

import com.github.squi2rel.freedraw.bukkit.brush.BrushPath;
import com.github.squi2rel.freedraw.bukkit.database.Database;
import com.github.squi2rel.freedraw.bukkit.network.PacketHandler;
import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Config/persistence and player lifecycle handling.
 *
 * <p>Path data and action logs are stored in an embedded SQLite database
 * ({@link Database}); all writes are asynchronous so the server thread never
 * blocks on disk I/O. The old {@code data.bin} format is no longer used.</p>
 */
public class DataHolder {
    public static Path configPath;
    public static Database db;
    public static ServerConfig config;
    public static RegionManager paths;
    /** Players that have sent their CONFIG (version) packet, i.e. FreeDraw clients. */
    public static HashMap<UUID, String> players = new HashMap<>();

    public static void load() {
        configPath = FreeDrawPlugin.instance.getDataFolder().toPath().resolve("config.json");
        config = loadConfig(ServerConfig.class, configPath);
        paths = new RegionManager(config.broadcastRange);

        // Close any previous database instance first (reload path).
        if (db != null) {
            db.close();
            db = null;
        }
        db = new Database(FreeDrawPlugin.instance.getDataFolder().toPath().resolve("freedraw.db").toFile());
        db.open();

        // Startup load is synchronous on purpose: paths must be in memory before
        // players can see them. Runtime writes are fully async.
        HashMap<UUID, BrushPath> map = new HashMap<>();
        for (BrushPath path : db.loadAllPaths()) {
            map.put(path.uuid, path);
        }
        if (map.isEmpty()) {
            // One-time migration from the legacy data.bin format (if present).
            Map<UUID, BrushPath> legacy = migrateLegacyDataBin();
            if (!legacy.isEmpty()) {
                map.putAll(legacy);
                for (BrushPath path : legacy.values()) db.savePath(path);
                FreeDrawPlugin.LOGGER.info("Migrated " + legacy.size() + " paths from legacy data.bin into SQLite");
            }
        }
        config.paths = map;
        long realPoints = 0, points = 0;
        for (BrushPath path : map.values()) {
            paths.insert(path);
            realPoints += path.points.size();
            points += path.size;
        }
        FreeDrawPlugin.LOGGER.info(String.format("Loaded %d paths with %d points, %d real points", map.size(), points, realPoints));
    }

    public static void save() {
        // Paths are persisted incrementally in the DB; config is saved here.
        saveConfig(config, configPath);
        if (db != null) db.flushAsync();
    }

    /** Called once per server tick; keeps region sync in step with player movement. */
    public static void update() {
        for (UUID uuid : players.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;
            Location loc = player.getLocation();
            paths.update(uuid, player.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ());
        }
    }

    public static void onPlayerJoin(Player player) {
        PacketHandler.sendTo(player, PacketHandler.config(FreeDrawPlugin.version, config));
        PacketHandler.sendTo(player, PacketHandler.maxPoints(config.maxPoints));
    }

    public static void onPlayerLeave(Player player) {
        paths.removePlayer(player.getUniqueId());
        players.remove(player.getUniqueId());
    }

    /** Reads the legacy data.bin format (pre-SQLite) and returns the stored paths. */
    private static Map<UUID, BrushPath> migrateLegacyDataBin() {
        java.io.File file = FreeDrawPlugin.instance.getDataFolder().toPath().resolve("data.bin").toFile();
        if (!file.exists()) return Map.of();
        Map<UUID, BrushPath> map = new HashMap<>();
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.util.zip.InflaterInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(file))))) {
            int size = in.readInt();
            for (int i = 0; i < size; i++) {
                UUID uuid = new UUID(in.readLong(), in.readLong());
                BrushPath path = new BrushPath(uuid, in.readUTF(),
                        new org.joml.Vector3d(in.readDouble(), in.readDouble(), in.readDouble()), in.readInt());
                path.finalized = true;
                path.minX = in.readFloat();
                path.minY = in.readFloat();
                path.minZ = in.readFloat();
                path.maxX = in.readFloat();
                path.maxY = in.readFloat();
                path.maxZ = in.readFloat();
                path.size = in.readInt();
                int points = in.readInt();
                path.points.ensureCapacity(points);
                for (int j = 0; j < points; j++) {
                    path.points.add(new org.joml.Vector3f(in.readFloat(), in.readFloat(), in.readFloat()));
                }
                map.put(uuid, path);
            }
        } catch (Exception e) {
            FreeDrawPlugin.LOGGER.warning("Failed to migrate legacy data.bin: " + e.getMessage());
        }
        return map;
    }

    public static <T> T loadConfig(Class<T> clazz, Path path) {
        try {
            return new Gson().fromJson(Files.readString(path), clazz);
        } catch (Exception e) {
            try {
                saveConfig(clazz.getDeclaredConstructor().newInstance(), path);
                return new Gson().fromJson(Files.readString(path), clazz);
            } catch (Exception ex) {
                RuntimeException th = new RuntimeException("Failed to load config file", ex);
                th.addSuppressed(e);
                throw th;
            }
        }
    }

    public static void saveConfig(Object config, Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, new Gson().toJson(config));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
