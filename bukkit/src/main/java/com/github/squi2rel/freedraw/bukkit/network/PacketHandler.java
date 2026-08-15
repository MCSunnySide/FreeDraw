package com.github.squi2rel.freedraw.bukkit.network;

import com.github.squi2rel.freedraw.bukkit.DataHolder;
import com.github.squi2rel.freedraw.bukkit.FreeDrawPlugin;
import com.github.squi2rel.freedraw.bukkit.ServerConfig;
import com.github.squi2rel.freedraw.bukkit.brush.BrushPath;
import com.github.squi2rel.freedraw.bukkit.database.ActionLog;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.joml.Vector3f;

import java.util.UUID;

import static com.github.squi2rel.freedraw.bukkit.DataHolder.config;
import static com.github.squi2rel.freedraw.bukkit.DataHolder.paths;
import static com.github.squi2rel.freedraw.bukkit.network.IOUtil.writeString;
import static com.github.squi2rel.freedraw.bukkit.network.PacketID.*;

/**
 * Server-side protocol handler. Mirrors
 * {@code com.github.squi2rel.freedraw.network.ServerPacketHandler} so a FreeDraw
 * Fabric client can talk to a Bukkit server verbatim.
 */
public class PacketHandler {

    public static void handle(Player player, PacketBuf buf) {
        int type = buf.readUnsignedByte();
        switch (type) {
            case CONFIG -> {
                String version = IOUtil.readString(buf, 16);
                // Answer a NEW client's CONFIG (its in-PLAY request or first reply) with a
                // full config push. The initial push from onPlayerJoin can be dropped when
                // it lands before the client finished the login/config phase (slow first
                // joins with VR/shaders, proxy channel propagation), which would otherwise
                // leave the client with connected=false for the whole session. Returning
                // null from put means the player was not in the map yet.
                if (DataHolder.players.put(player.getUniqueId(), version) == null) {
                    sendTo(player, config(FreeDrawPlugin.version, config));
                    sendTo(player, maxPoints(config.maxPoints));
                }
            }
            case NEW_PATH -> {
                if (!player.hasPermission("freedraw.draw")) return;
                UUID old = IOUtil.readUUID(buf);
                UUID uuid = UUID.randomUUID();
                BrushPath path = new BrushPath(uuid, player.getWorld().getName(), IOUtil.readVec3d(buf), buf.readInt());
                config.paths.put(uuid, path);
                sendTo(player, newPath(old, uuid, path.color));
            }
            case REMOVE_PATH -> {
                if (!player.hasPermission("freedraw.erase")) return;
                UUID uuid = IOUtil.readUUID(buf);
                BrushPath path = config.paths.remove(uuid);
                if (path == null) return;
                paths.remove(path);
                sendTo(player, removePath(uuid));
                // Async: log the erase (with full path data so it can be restored) and drop it from storage.
                DataHolder.db.logAction(ActionLog.erase(path, player.getUniqueId(), player.getName()));
                DataHolder.db.deletePath(uuid);
                FreeDrawPlugin.LOGGER.info(String.format("Player %s removed %d points with %s", player.getName(), path.size, path));
            }
            case ADD_POINTS -> {
                int index = buf.readerIndex();
                UUID uuid = IOUtil.readUUID(buf);
                BrushPath path = config.paths.get(uuid);
                if (path == null || path.finalized) throw new RuntimeException("Invalid path!");
                int size = buf.readInt();
                Vector3f prev;
                if (path.points.isEmpty()) {
                    prev = IOUtil.readVec3f(buf);
                    path.points.add(prev);
                    path.size++;
                    size--;
                } else {
                    prev = path.points.get(path.points.size() - 1);
                }
                for (int i = 0; i < size; i++) {
                    Vector3f now = IOUtil.readVec3f(buf);
                    path.points.add(now);
                    path.size += Math.max(1, (int) (prev.distance(now) * BrushPath.SPLINE_STEPS));
                    path.updateBounds(now.x, now.y, now.z);
                    prev = now;
                }
                if (buf.readBoolean()) {
                    if (path.points.size() < 3) {
                        config.paths.remove(uuid);
                        sendTo(player, removePath(uuid));
                        return;
                    }
                    path.stop();
                    paths.insert(path);
                    // Async: persist the finalized path and log the DRAW action.
                    DataHolder.db.savePath(path);
                    DataHolder.db.logAction(ActionLog.draw(path, player.getUniqueId(), player.getName()));
                    FreeDrawPlugin.LOGGER.info(String.format("Player %s created %d points with %s", player.getName(), path.size, path));
                }
                int length = buf.readerIndex() - index;
                buf.readerIndex(index);
                byte[] data = buf.readBytes(length);
                broadcast(player, data);
            }
            default -> player.kickPlayer("Unknown packet type: " + type);
        }
        if (buf.readableBytes() > 0) {
            player.kickPlayer("Illegal packet! Remaining: " + buf.readableBytes());
        }
    }

    /** Forward a raw ADD_POINTS message to every player within broadcastRange (same world), excluding the sender. */
    private static void broadcast(Player pos, byte[] data) {
        double range = config.broadcastRange;
        double rangeSq = range * range;
        Location loc = pos.getLocation();
        for (Player player : pos.getWorld().getPlayers()) {
            if (player == pos) continue;
            if (player.getLocation().distanceSquared(loc) <= rangeSq) {
                sendTo(player, data);
            }
        }
    }

    private static PacketBuf create(int id) {
        PacketBuf buf = PacketBuf.buffer();
        buf.writeByte((byte) id);
        return buf;
    }

    private static byte[] toByteArray(PacketBuf buf) {
        return buf.toByteArray();
    }

    /** Send a raw FreeDraw payload to a player over the freedraw:payload plugin channel. */
    public static void sendTo(Player player, byte[] bytes) {
        player.sendPluginMessage(FreeDrawPlugin.instance, FreeDrawPlugin.CHANNEL, bytes);
    }

    public static byte[] config(String version, ServerConfig config) {
        PacketBuf buf = create(CONFIG);
        writeString(buf, version);
        writeString(buf, config.brushItem);
        buf.writeFloat(config.brushIdStart);
        buf.writeFloat(config.brushIdEnd);
        IOUtil.writeQuaternion(buf, config.brushQuat);
        buf.writeFloat(config.brushLength);
        writeString(buf, config.eraserItem);
        buf.writeFloat(config.eraserId);
        IOUtil.writeQuaternion(buf, config.eraserQuat);
        buf.writeFloat(config.eraserLength);
        buf.writeInt(config.maxPoints);
        buf.writeInt(config.uploadInterval);
        buf.writeInt(config.defaultColor);
        buf.writeFloat(config.desktopRange);
        return toByteArray(buf);
    }

    public static byte[] newPath(UUID old, UUID uuid, int color) {
        PacketBuf buf = create(NEW_PATH);
        IOUtil.writeUUID(buf, old);
        IOUtil.writeUUID(buf, uuid);
        buf.writeInt(color);
        return toByteArray(buf);
    }

    public static byte[] createPath(BrushPath path) {
        PacketBuf buf = create(CREATE_PATH);
        IOUtil.writeUUID(buf, path.uuid);
        IOUtil.writeVec3d(buf, path.offset);
        buf.writeInt(path.color);
        buf.writeBoolean(path.finalized);
        return toByteArray(buf);
    }

    public static byte[] addPoints(BrushPath path) {
        PacketBuf buf = create(ADD_POINTS);
        IOUtil.writeUUID(buf, path.uuid);
        buf.writeInt(path.points.size());
        for (Vector3f point : path.points) {
            IOUtil.writeVec3f(buf, point);
        }
        buf.writeBoolean(true);
        return toByteArray(buf);
    }

    public static byte[] removePath(UUID uuid) {
        PacketBuf buf = create(REMOVE_PATH);
        IOUtil.writeUUID(buf, uuid);
        return toByteArray(buf);
    }

    public static byte[] maxPoints(int maxPoints) {
        PacketBuf buf = create(MAX_POINTS);
        buf.writeInt(maxPoints);
        return toByteArray(buf);
    }

    public static byte[] color(int color) {
        PacketBuf buf = create(COLOR);
        buf.writeInt(color);
        return toByteArray(buf);
    }
}
