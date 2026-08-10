package com.github.squi2rel.freedraw.bukkit;

import com.github.squi2rel.freedraw.bukkit.brush.BrushPath;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spatial index used to find which paths are inside a player's broadcast range.
 * Mirrors {@code com.github.squi2rel.freedraw.SpatialHash}.
 */
public class SpatialHash {
    private final float cellSize;
    private final Map<GridIndex, Set<BrushPath>> gridMap = new ConcurrentHashMap<>();
    private int size;

    public SpatialHash(float size) {
        this.cellSize = size;
    }

    private Set<GridIndex> getIndices(BrushPath path) {
        int minX = (int) Math.floor((path.offset.x + path.minX) / cellSize);
        int minY = (int) Math.floor((path.offset.y + path.minY) / cellSize);
        int minZ = (int) Math.floor((path.offset.z + path.minZ) / cellSize);
        int maxX = (int) Math.floor((path.offset.x + path.maxX) / cellSize);
        int maxY = (int) Math.floor((path.offset.y + path.maxY) / cellSize);
        int maxZ = (int) Math.floor((path.offset.z + path.maxZ) / cellSize);

        Set<GridIndex> indices = new HashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    indices.add(new GridIndex(x, y, z));
                }
            }
        }
        return indices;
    }

    public void insert(BrushPath path) {
        for (GridIndex idx : getIndices(path)) {
            gridMap.computeIfAbsent(idx, k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(path);
            size++;
        }
    }

    public void remove(BrushPath path) {
        for (GridIndex idx : getIndices(path)) {
            Set<BrushPath> set = gridMap.get(idx);
            if (set != null) {
                if (set.remove(path)) size--;
                if (set.isEmpty()) gridMap.remove(idx);
            }
        }
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public Set<BrushPath> get(double px, double py, double pz, double range) {
        int minX = (int) Math.floor((px - range) / cellSize);
        int minY = (int) Math.floor((py - range) / cellSize);
        int minZ = (int) Math.floor((pz - range) / cellSize);
        int maxX = (int) Math.floor((px + range) / cellSize);
        int maxY = (int) Math.floor((py + range) / cellSize);
        int maxZ = (int) Math.floor((pz + range) / cellSize);

        Set<BrushPath> result = new HashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Set<BrushPath> bucket = gridMap.get(new GridIndex(x, y, z));
                    if (bucket != null) {
                        result.addAll(bucket);
                    }
                }
            }
        }
        return result;
    }

    public record GridIndex(int x, int y, int z) {}
}
