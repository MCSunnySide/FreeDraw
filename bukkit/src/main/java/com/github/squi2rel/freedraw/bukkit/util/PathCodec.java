package com.github.squi2rel.freedraw.bukkit.util;

import com.github.squi2rel.freedraw.bukkit.brush.BrushPath;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Binary serialization of {@link BrushPath} (world, offset, color, bounds, size,
 * and all points) for storing in the database. This lets the plugin restore an
 * erased path during a rollback.
 */
public final class PathCodec {

    private PathCodec() {
    }

    public static byte[] encode(BrushPath path) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF(path.world);
            out.writeDouble(path.offset.x);
            out.writeDouble(path.offset.y);
            out.writeDouble(path.offset.z);
            out.writeInt(path.color);
            out.writeFloat(path.minX);
            out.writeFloat(path.minY);
            out.writeFloat(path.minZ);
            out.writeFloat(path.maxX);
            out.writeFloat(path.maxY);
            out.writeFloat(path.maxZ);
            out.writeInt(path.size);
            out.writeInt(path.points.size());
            for (Vector3f p : path.points) {
                out.writeFloat(p.x);
                out.writeFloat(p.y);
                out.writeFloat(p.z);
            }
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode BrushPath", e);
        }
    }

    public static BrushPath decode(UUID uuid, byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            String world = in.readUTF();
            Vector3d offset = new Vector3d(in.readDouble(), in.readDouble(), in.readDouble());
            int color = in.readInt();
            BrushPath path = new BrushPath(uuid, world, offset, color);
            path.minX = in.readFloat();
            path.minY = in.readFloat();
            path.minZ = in.readFloat();
            path.maxX = in.readFloat();
            path.maxY = in.readFloat();
            path.maxZ = in.readFloat();
            path.size = in.readInt();
            int points = in.readInt();
            path.points.ensureCapacity(points);
            for (int i = 0; i < points; i++) {
                path.points.add(new Vector3f(in.readFloat(), in.readFloat(), in.readFloat()));
            }
            path.finalized = true;
            return path;
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode BrushPath", e);
        }
    }
}
