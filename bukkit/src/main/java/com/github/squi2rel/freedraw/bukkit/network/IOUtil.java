package com.github.squi2rel.freedraw.bukkit.network;

import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Wire-format helpers. Mirrors {@code com.github.squi2rel.freedraw.network.IOUtil}.
 * The DataInput/DataOutput overloads are used for the on-disk data.bin format.
 */
public class IOUtil {
    public static String readString(PacketBuf buf, int maxLength) {
        int len = buf.readUnsignedShort();
        if (len > maxLength) {
            throw new IllegalStateException(String.format("length(%d) exceeds max length(%d)", len, maxLength));
        }
        return new String(buf.readBytes(len), StandardCharsets.UTF_8);
    }

    public static void writeString(PacketBuf buf, String str) {
        byte[] data = str.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(data.length);
        buf.writeBytes(data);
    }

    public static Quaternionf readQuaternion(PacketBuf buf) {
        return new Quaternionf(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    public static void writeQuaternion(PacketBuf buf, Quaternionf q) {
        buf.writeFloat(q.x);
        buf.writeFloat(q.y);
        buf.writeFloat(q.z);
        buf.writeFloat(q.w);
    }

    public static void writeVec3f(PacketBuf buf, Vector3f v) {
        buf.writeFloat(v.x);
        buf.writeFloat(v.y);
        buf.writeFloat(v.z);
    }

    public static void writeVec3f(DataOutput out, Vector3f v) throws IOException {
        out.writeFloat(v.x);
        out.writeFloat(v.y);
        out.writeFloat(v.z);
    }

    public static Vector3f readVec3f(PacketBuf buf) {
        return new Vector3f(buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    public static Vector3f readVec3f(DataInput in) throws IOException {
        return new Vector3f(in.readFloat(), in.readFloat(), in.readFloat());
    }

    public static Vector3d readVec3d(PacketBuf buf) {
        return new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    public static Vector3d readVec3d(DataInput in) throws IOException {
        return new Vector3d(in.readDouble(), in.readDouble(), in.readDouble());
    }

    public static void writeVec3d(PacketBuf buf, Vector3d v) {
        buf.writeDouble(v.x);
        buf.writeDouble(v.y);
        buf.writeDouble(v.z);
    }

    public static void writeVec3d(DataOutput out, Vector3d v) throws IOException {
        out.writeDouble(v.x);
        out.writeDouble(v.y);
        out.writeDouble(v.z);
    }

    public static UUID readUUID(PacketBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }

    public static void writeUUID(PacketBuf buf, UUID uuid) {
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
    }
}
