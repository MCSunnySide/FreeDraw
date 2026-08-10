package com.github.squi2rel.freedraw.network;

import com.github.squi2rel.freedraw.DataHolder;
import com.github.squi2rel.freedraw.FreeDraw;
import com.github.squi2rel.freedraw.ServerConfig;
import com.github.squi2rel.freedraw.brush.BrushPath;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.joml.Vector3f;

import java.util.UUID;

import static com.github.squi2rel.freedraw.DataHolder.config;
import static com.github.squi2rel.freedraw.DataHolder.paths;
import static com.github.squi2rel.freedraw.network.IOUtil.writeString;
import static com.github.squi2rel.freedraw.network.PacketID.*;

public class ServerPacketHandler {

    public static void handle(ServerPlayerEntity player, ByteBuf buf) {
        short type = buf.readUnsignedByte();
        switch (type) {
            case CONFIG -> DataHolder.players.put(player.getUuid(), IOUtil.readString(buf, 16));
            case NEW_PATH -> {
                UUID old = IOUtil.readUUID(buf);
                UUID uuid = UUID.randomUUID();
                BrushPath path = new BrushPath(uuid, player.getEntityWorld().getRegistryKey().getValue().toString(), IOUtil.readVec3d(buf), buf.readInt());
                config.paths.put(uuid, path);
                sendTo(player, newPath(old, uuid, path.color));
            }
            case REMOVE_PATH -> {
                UUID uuid = IOUtil.readUUID(buf);
                BrushPath path = config.paths.remove(uuid);
                if (path == null) return;
                paths.remove(path);
                sendTo(player, removePath(uuid));
                FreeDraw.LOGGER.info("Player {} removed {} points with {}", player.getName().getString(), path.size, path);
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
                    prev = path.points.getLast();
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
                    FreeDraw.LOGGER.info("Player {} created {} points with {}", player.getName().getString(), path.size, path);
                }
                byte[] data = new byte[buf.readerIndex() - index];
                buf.readerIndex(index);
                buf.readBytes(data);
                broadcast(player, data);
            }
            default -> player.networkHandler.disconnect(Text.of("Unknown packet type: " + type));
        }
        if (buf.readableBytes() > 0) {
            player.networkHandler.disconnect(Text.of("Illegal packet! Remaining: " + buf.readableBytes()));
        }
    }

    private static void broadcast(ServerPlayerEntity pos, byte[] data) {
        for (ServerPlayerEntity player : PlayerLookup.around(pos.getEntityWorld(), pos.getEntityPos(), config.broadcastRange)) {
            if (player == pos) continue;
            sendTo(player, data);
        }
    }

    private static ByteBuf create(int id) {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer();
        buf.writeByte((byte) id);
        return buf;
    }

    private static byte[] toByteArray(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        return bytes;
    }

    public static void sendTo(ServerPlayerEntity player, byte[] bytes) {
        ServerPlayNetworking.send(player, new DrawPayload(bytes));
    }

    public static byte[] config(String version, ServerConfig config) {
        ByteBuf buf = create(CONFIG);
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
        ByteBuf buf = create(NEW_PATH);
        IOUtil.writeUUID(buf, old);
        IOUtil.writeUUID(buf, uuid);
        buf.writeInt(color);
        return toByteArray(buf);
    }

    public static byte[] createPath(BrushPath path) {
        ByteBuf buf = create(CREATE_PATH);
        IOUtil.writeUUID(buf, path.uuid);
        IOUtil.writeVec3d(buf, path.offset);
        buf.writeInt(path.color);
        buf.writeBoolean(path.finalized);
        return toByteArray(buf);
    }

    public static byte[] addPoints(BrushPath path) {
        ByteBuf buf = create(ADD_POINTS);
        IOUtil.writeUUID(buf, path.uuid);
        buf.writeInt(path.points.size());
        for (Vector3f point : path.points) {
            IOUtil.writeVec3f(buf, point);
        }
        buf.writeBoolean(true);
        return toByteArray(buf);
    }

    public static byte[] removePath(UUID uuid) {
        ByteBuf buf = create(REMOVE_PATH);
        IOUtil.writeUUID(buf, uuid);
        return toByteArray(buf);
    }

    public static byte[] maxPoints(int maxPoints) {
        ByteBuf buf = create(MAX_POINTS);
        buf.writeInt(maxPoints);
        return toByteArray(buf);
    }

    public static byte[] color(int color) {
        ByteBuf buf = create(COLOR);
        buf.writeInt(color);
        return toByteArray(buf);
    }
}
