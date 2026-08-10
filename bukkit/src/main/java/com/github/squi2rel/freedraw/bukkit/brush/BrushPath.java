package com.github.squi2rel.freedraw.bukkit.brush;

import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Server-side representation of a single drawn path.
 * Mirrors {@code com.github.squi2rel.freedraw.brush.BrushPath}.
 */
public class BrushPath {
    public UUID uuid;
    public String world;
    /** World-space position the path's local points are relative to. */
    public Vector3d offset;
    public ArrayList<Vector3f> points = new ArrayList<>();
    public int size = 0;
    public float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
    public float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
    public boolean finalized = false;
    public int color;

    public static final int SPLINE_STEPS = 20;

    public BrushPath(UUID uuid, String world, Vector3d offset, int color) {
        this.uuid = uuid;
        this.world = world;
        this.offset = offset;
        this.color = color;
    }

    public void updateBounds(float x, float y, float z) {
        if (x < minX) minX = x;
        if (y < minY) minY = y;
        if (z < minZ) minZ = z;
        if (x > maxX) maxX = x;
        if (y > maxY) maxY = y;
        if (z > maxZ) maxZ = z;
    }

    public void stop() {
        finalized = true;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof BrushPath p && p.uuid.equals(uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }

    @Override
    public String toString() {
        return "BrushPath[uuid=" + uuid + ", offset=" + offset + "]";
    }
}
