package com.github.squi2rel.freedraw;

import com.github.squi2rel.freedraw.brush.BrushPath;
import com.github.squi2rel.freedraw.network.IOUtil;
import com.github.squi2rel.freedraw.network.ServerPacketHandler;
import com.google.gson.Gson;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import static com.github.squi2rel.freedraw.FreeDraw.*;

public class DataHolder {
    public static File pointsPath;
    public static ServerConfig config;
    public static RegionManager paths;
    public static HashMap<UUID, String> players = new HashMap<>();

    public static void load() {
        config = loadConfig(ServerConfig.class, FreeDraw.configPath);
        paths = new RegionManager(config.broadcastRange);
        pointsPath = configPath.getParent().resolve("data.bin").toFile();
        HashMap<UUID, BrushPath> map = new HashMap<>();
        loadPoints(map);
        config.paths = map;
        long realPoints = 0, points = 0;
        for (BrushPath path : map.values()) {
            paths.insert(path);
            realPoints += path.points.size();
            points += path.size;
        }
        LOGGER.info("Loaded {} paths with {} points, {} real points", map.size(), points, realPoints);
    }

    public static void save() {
        savePoints(config.paths);
        saveConfig(config, FreeDraw.configPath);
    }

    public static void update() {
        PlayerManager pm = server.getPlayerManager();
        for (UUID uuid : players.keySet()) {
            ServerPlayerEntity player = pm.getPlayer(uuid);
            Vec3d pos = Objects.requireNonNull(player).getEntityPos();
            paths.update(player.getUuid(), player.getEntityWorld().getRegistryKey().getValue().toString(), pos.x, pos.y, pos.z);
        }
    }

    public static void onPlayerJoin(ServerPlayerEntity player) {
        ServerPacketHandler.sendTo(player, ServerPacketHandler.config(FreeDraw.version, config));
        ServerPacketHandler.sendTo(player, ServerPacketHandler.maxPoints(config.maxPoints));
    }

    public static void onPlayerLeave(ServerPlayerEntity player) {
        paths.removePlayer(player.getUuid());
        players.remove(player.getUuid());
    }

    public static void savePoints(Map<UUID, BrushPath> map) {
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(new BufferedOutputStream(new FileOutputStream(pointsPath))))) {
            out.writeInt(map.size());
            for (Map.Entry<UUID, BrushPath> entry : map.entrySet()) {
                UUID uuid = entry.getKey();
                BrushPath path = entry.getValue();
                out.writeLong(uuid.getMostSignificantBits());
                out.writeLong(uuid.getLeastSignificantBits());
                out.writeUTF(path.world);
                IOUtil.writeVec3d(out, path.offset);
                out.writeInt(path.color);
                out.writeFloat(path.minX);
                out.writeFloat(path.minY);
                out.writeFloat(path.minZ);
                out.writeFloat(path.maxX);
                out.writeFloat(path.maxY);
                out.writeFloat(path.maxZ);
                out.writeInt(path.size);
                out.writeInt(path.points.size());
                for (Vector3f point : path.points) {
                    IOUtil.writeVec3f(out, point);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void loadPoints(Map<UUID, BrushPath> map) {
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new BufferedInputStream(new FileInputStream(pointsPath))))) {
            int size = in.readInt();
            for (int i = 0; i < size; i++) {
                UUID uuid = new UUID(in.readLong(), in.readLong());
                BrushPath path = new BrushPath(uuid, in.readUTF(), IOUtil.readVec3d(in), in.readInt());
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
                    path.points.add(IOUtil.readVec3f(in));
                }
                map.put(uuid, path);
            }
        } catch (Exception ignored) {
        }
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
