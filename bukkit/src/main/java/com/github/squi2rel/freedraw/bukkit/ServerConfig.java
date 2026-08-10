package com.github.squi2rel.freedraw.bukkit;

import com.github.squi2rel.freedraw.bukkit.brush.BrushPath;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.UUID;

/**
 * Server configuration, serialized to config.json with Gson.
 * Field names/defaults match {@code com.github.squi2rel.freedraw.ServerConfig}
 * so the client mod receives identical settings.
 */
public class ServerConfig {
    public String brushItem = "minecraft:brush", eraserItem = "minecraft:resin_brick";
    public float brushIdStart = -1, brushIdEnd = -1, eraserId = -1;
    public int maxPoints = 2048;
    public int broadcastRange = 128;
    public Quaternionf brushQuat = new Quaternionf(), eraserQuat = new Quaternionf();
    public float brushLength = 0.1f, eraserLength = 0.1f;
    public int uploadInterval = 100;
    public int defaultColor = 0xFFFF0000;
    public float desktopRange = 2;

    /** All paths (in-progress + finalized). Not persisted directly. */
    public transient HashMap<UUID, BrushPath> paths = new HashMap<>();
}
