package com.github.squi2rel.freedraw.bukkit.database;

import com.github.squi2rel.freedraw.bukkit.brush.BrushPath;

import java.util.UUID;

/**
 * A single recorded drawing/erasing action, mirroring CoreProtect's action log.
 *
 * <ul>
 *   <li>{@code DRAW}  - a player finalized a path (kept in the world).</li>
 *   <li>{@code ERASE} - a player erased a path; the full path data is stored so it can be restored.</li>
 * </ul>
 *
 * Rollback of a DRAW deletes the path; rollback of an ERASE restores it.
 */
public class ActionLog {

    public enum Type {
        DRAW,
        ERASE
    }

    /** Database row id (0 when not yet persisted). */
    public long id;
    /** Epoch milliseconds when the action happened. */
    public long timestamp;
    public UUID playerUuid;
    public String playerName;
    public Type type;
    public String world;
    public UUID pathUuid;
    public float minX, minY, minZ, maxX, maxY, maxZ;
    public int color;
    public int pointCount;
    /** Full path bytes for ERASE actions (null for DRAW, which can be read from the paths table). */
    public byte[] pathData;
    /** Whether this action has been rolled back. */
    public boolean rolledBack;

    public static ActionLog draw(BrushPath path, UUID playerUuid, String playerName) {
        ActionLog log = base(path, playerUuid, playerName);
        log.type = Type.DRAW;
        // Store the full path data for BOTH types: rollback deletes it, redo restores it.
        log.pathData = com.github.squi2rel.freedraw.bukkit.util.PathCodec.encode(path);
        return log;
    }

    public static ActionLog erase(BrushPath path, UUID playerUuid, String playerName) {
        ActionLog log = base(path, playerUuid, playerName);
        log.type = Type.ERASE;
        log.pathData = com.github.squi2rel.freedraw.bukkit.util.PathCodec.encode(path);
        return log;
    }

    private static ActionLog base(BrushPath path, UUID playerUuid, String playerName) {
        ActionLog log = new ActionLog();
        log.timestamp = System.currentTimeMillis();
        log.playerUuid = playerUuid;
        log.playerName = playerName;
        log.world = path.world;
        log.pathUuid = path.uuid;
        log.minX = path.minX;
        log.minY = path.minY;
        log.minZ = path.minZ;
        log.maxX = path.maxX;
        log.maxY = path.maxY;
        log.maxZ = path.maxZ;
        log.color = path.color;
        log.pointCount = path.points.size();
        return log;
    }
}
