package com.github.squi2rel.freedraw.bukkit.database;

import com.github.squi2rel.freedraw.bukkit.FreeDrawPlugin;
import com.github.squi2rel.freedraw.bukkit.brush.BrushPath;
import com.github.squi2rel.freedraw.bukkit.util.PathCodec;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Async SQLite persistence layer.
 *
 * <p>All database work runs on a single dedicated daemon thread so the server
 * main thread never blocks on I/O. Path writes and action-log writes are batched
 * and committed in a single transaction every {@link #FLUSH_INTERVAL_MS} to keep
 * the disk write rate low.</p>
 */
public class Database implements AutoCloseable {

    private static final long FLUSH_INTERVAL_MS = 1000L;
    private static final int MAX_PENDING = 500;

    private final File file;
    private Connection conn;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FreeDraw-DB");
        t.setDaemon(true);
        return t;
    });

    // Batch accumulation (single-threaded, only touched by the executor thread).
    private final List<Runnable> pending = new ArrayList<>();
    private volatile boolean closed = false;

    public Database(File file) {
        this.file = file;
    }

    /** Opens the connection and creates tables. Call once from the main thread at enable. */
    public void open() {
        try {
            Class.forName("org.sqlite.JDBC");
            file.getParentFile().mkdirs();
            conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS paths (
                            uuid       TEXT PRIMARY KEY,
                            world      TEXT NOT NULL,
                            color      INTEGER NOT NULL,
                            bounds_min_x REAL NOT NULL, bounds_min_y REAL NOT NULL, bounds_min_z REAL NOT NULL,
                            bounds_max_x REAL NOT NULL, bounds_max_y REAL NOT NULL, bounds_max_z REAL NOT NULL,
                            size       INTEGER NOT NULL DEFAULT 0,
                            points     BLOB NOT NULL,
                            created_at INTEGER NOT NULL,
                            created_by TEXT
                        )""");
                st.execute("CREATE INDEX IF NOT EXISTS idx_paths_world ON paths(world)");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS actions (
                            id          INTEGER PRIMARY KEY AUTOINCREMENT,
                            ts          INTEGER NOT NULL,
                            player_uuid TEXT,
                            player_name TEXT,
                            action      TEXT NOT NULL,
                            world       TEXT NOT NULL,
                            path_uuid   TEXT NOT NULL,
                            min_x REAL, min_y REAL, min_z REAL,
                            max_x REAL, max_y REAL, max_z REAL,
                            color       INTEGER,
                            point_count INTEGER NOT NULL DEFAULT 0,
                            path_data   BLOB,
                            rolled_back INTEGER NOT NULL DEFAULT 0
                        )""");
                st.execute("CREATE INDEX IF NOT EXISTS idx_actions_ts ON actions(ts)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_actions_player ON actions(player_uuid)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_actions_world ON actions(world)");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to open FreeDraw database", e);
        }
        // Periodic batch flush.
        executor.execute(this::flushLoop);
    }

    private void flushLoop() {
        while (!closed) {
            try {
                Thread.sleep(FLUSH_INTERVAL_MS);
                flushNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                FreeDrawPlugin.LOGGER.warning("FreeDraw DB flush error: " + e.getMessage());
            }
        }
    }

    /**
     * Synchronously loads all stored paths (used once at startup; blocking is
     * acceptable here because the plugin cannot serve data before this finishes).
     */
    public List<BrushPath> loadAllPaths() {
        List<BrushPath> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid, points FROM paths");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString(1));
                result.add(PathCodec.decode(uuid, rs.getBytes(2)));
            }
        } catch (SQLException e) {
            FreeDrawPlugin.LOGGER.warning("Failed to load paths: " + e.getMessage());
        }
        return result;
    }

    /** Async: upsert a path. */
    public void savePath(BrushPath path) {
        submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO paths (uuid, world, color, bounds_min_x, bounds_min_y, bounds_min_z,
                                       bounds_max_x, bounds_max_y, bounds_max_z, size, points, created_at, created_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(uuid) DO UPDATE SET
                        world=excluded.world, color=excluded.color,
                        bounds_min_x=excluded.bounds_min_x, bounds_min_y=excluded.bounds_min_y, bounds_min_z=excluded.bounds_min_z,
                        bounds_max_x=excluded.bounds_max_x, bounds_max_y=excluded.bounds_max_y, bounds_max_z=excluded.bounds_max_z,
                        size=excluded.size, points=excluded.points
                    """)) {
                ps.setString(1, path.uuid.toString());
                ps.setString(2, path.world);
                ps.setInt(3, path.color);
                ps.setFloat(4, path.minX);
                ps.setFloat(5, path.minY);
                ps.setFloat(6, path.minZ);
                ps.setFloat(7, path.maxX);
                ps.setFloat(8, path.maxY);
                ps.setFloat(9, path.maxZ);
                ps.setInt(10, path.size);
                ps.setBytes(11, PathCodec.encode(path));
                ps.setLong(12, System.currentTimeMillis());
                ps.setString(13, null);
                ps.executeUpdate();
            } catch (SQLException e) {
                FreeDrawPlugin.LOGGER.warning("Failed to save path " + path.uuid + ": " + e.getMessage());
            }
        });
    }

    /** Async: delete a path. */
    public void deletePath(UUID uuid) {
        submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM paths WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                FreeDrawPlugin.LOGGER.warning("Failed to delete path " + uuid + ": " + e.getMessage());
            }
        });
    }

    /** Async: record an action log entry. */
    public void logAction(ActionLog log) {
        submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO actions (ts, player_uuid, player_name, action, world, path_uuid,
                                         min_x, min_y, min_z, max_x, max_y, max_z, color, point_count, path_data)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                ps.setLong(1, log.timestamp);
                ps.setString(2, log.playerUuid != null ? log.playerUuid.toString() : null);
                ps.setString(3, log.playerName);
                ps.setString(4, log.type.name());
                ps.setString(5, log.world);
                ps.setString(6, log.pathUuid.toString());
                ps.setFloat(7, log.minX);
                ps.setFloat(8, log.minY);
                ps.setFloat(9, log.minZ);
                ps.setFloat(10, log.maxX);
                ps.setFloat(11, log.maxY);
                ps.setFloat(12, log.maxZ);
                ps.setInt(13, log.color);
                ps.setInt(14, log.pointCount);
                ps.setBytes(15, log.pathData);
                ps.executeUpdate();
            } catch (SQLException e) {
                FreeDrawPlugin.LOGGER.warning("Failed to log action: " + e.getMessage());
            }
        });
    }

    /** Async: mark actions as rolled back. */
    public void markRolledBack(List<Long> ids) {
        submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE actions SET rolled_back=1 WHERE id=?")) {
                for (long id : ids) {
                    ps.setLong(1, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                FreeDrawPlugin.LOGGER.warning("Failed to mark rolled back: " + e.getMessage());
            }
        });
    }

    /** Async: clear the rolled_back flag (used by /freedraw redo so the action can be rolled back again). */
    public void markNotRolledBack(List<Long> ids) {
        submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE actions SET rolled_back=0 WHERE id=?")) {
                for (long id : ids) {
                    ps.setLong(1, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                FreeDrawPlugin.LOGGER.warning("Failed to clear rolled back: " + e.getMessage());
            }
        });
    }

    /**
     * Async: query rolled-back action logs (for /freedraw redo).
     * Same filters as {@link #queryActions} but matches {@code rolled_back=1}.
     */
    public CompletableFuture<List<ActionLog>> queryRolledBackActions(UUID playerUuid, long sinceTs, String world,
                                                                     double centerX, double centerZ, double radius) {
        return queryActionsInternal(playerUuid, sinceTs, world, centerX, centerZ, radius, true);
    }

    /**
     * Async: query action logs.
     *
     * @param playerUuid filter by player, or null for all players
     * @param sinceTs     earliest timestamp (epoch ms), or 0
     * @param world       filter by world name, or null
     * @param centerX/Z   + radius filter on path bounds, or NaN to disable
     */
    public CompletableFuture<List<ActionLog>> queryActions(UUID playerUuid, long sinceTs, String world,
                                                           double centerX, double centerZ, double radius) {
        return queryActionsInternal(playerUuid, sinceTs, world, centerX, centerZ, radius, false);
    }

    private CompletableFuture<List<ActionLog>> queryActionsInternal(UUID playerUuid, long sinceTs, String world,
                                                                    double centerX, double centerZ, double radius,
                                                                    boolean rolledBackOnly) {
        CompletableFuture<List<ActionLog>> future = new CompletableFuture<>();
        submit(() -> {
            try {
                StringBuilder sql = new StringBuilder("""
                        SELECT id, ts, player_uuid, player_name, action, world, path_uuid,
                               min_x, min_y, min_z, max_x, max_y, max_z, color, point_count, path_data, rolled_back
                        FROM actions WHERE rolled_back=?
                        """);
                List<Object> args = new ArrayList<>();
                args.add(rolledBackOnly ? 1 : 0);
                if (playerUuid != null) {
                    sql.append(" AND player_uuid=?");
                    args.add(playerUuid.toString());
                }
                if (sinceTs > 0) {
                    sql.append(" AND ts>=?");
                    args.add(sinceTs);
                }
                if (world != null) {
                    sql.append(" AND world=?");
                    args.add(world);
                }
                if (!Double.isNaN(radius)) {
                    // Use a bounding-box overlap test on the action's bounds (index-friendly).
                    sql.append(" AND min_x <= ? AND max_x >= ? AND min_z <= ? AND max_z >= ?");
                    double maxX = centerX + radius, minX = centerX - radius;
                    double maxZ = centerZ + radius, minZ = centerZ - radius;
                    args.add(maxX);
                    args.add(minX);
                    args.add(maxZ);
                    args.add(minZ);
                }
                sql.append(" ORDER BY id");
                List<ActionLog> logs = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                    for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            ActionLog log = new ActionLog();
                            log.id = rs.getLong(1);
                            log.timestamp = rs.getLong(2);
                            String pu = rs.getString(3);
                            log.playerUuid = pu != null ? UUID.fromString(pu) : null;
                            log.playerName = rs.getString(4);
                            log.type = ActionLog.Type.valueOf(rs.getString(5));
                            log.world = rs.getString(6);
                            log.pathUuid = UUID.fromString(rs.getString(7));
                            log.minX = rs.getFloat(8);
                            log.minY = rs.getFloat(9);
                            log.minZ = rs.getFloat(10);
                            log.maxX = rs.getFloat(11);
                            log.maxY = rs.getFloat(12);
                            log.maxZ = rs.getFloat(13);
                            log.color = rs.getInt(14);
                            log.pointCount = rs.getInt(15);
                            log.pathData = rs.getBytes(16);
                            log.rolledBack = rs.getInt(17) != 0;
                            logs.add(log);
                        }
                    }
                }
                future.complete(logs);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /** Async: fetch the path data for a given path uuid (used when rolling back a draw to delete it). */
    public CompletableFuture<BrushPath> loadPath(UUID uuid) {
        CompletableFuture<BrushPath> future = new CompletableFuture<>();
        submit(() -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT points FROM paths WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) future.complete(PathCodec.decode(uuid, rs.getBytes(1)));
                    else future.complete(null);
                }
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /** Async: commit accumulated batched writes. Public so the main thread can force a flush on disable. */
    public void flushAsync() {
        submit(this::flushNow);
    }

    private void submit(Runnable task) {
        if (closed) return;
        synchronized (pending) {
            pending.add(task);
            if (pending.size() >= MAX_PENDING) {
                List<Runnable> batch = new ArrayList<>(pending);
                pending.clear();
                executor.execute(() -> runBatch(batch));
            }
        }
    }

    private void flushNow() {
        List<Runnable> batch;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        runBatch(batch);
    }

    private void runBatch(List<Runnable> batch) {
        boolean oldAutoCommit = true;
        try {
            oldAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            for (Runnable task : batch) {
                try {
                    task.run();
                } catch (Exception e) {
                    FreeDrawPlugin.LOGGER.warning("DB batch item error: " + e.getMessage());
                }
            }
            conn.commit();
        } catch (SQLException e) {
            FreeDrawPlugin.LOGGER.warning("DB commit error: " + e.getMessage());
            try {
                conn.rollback();
            } catch (SQLException ignored) {
            }
        } finally {
            try {
                conn.setAutoCommit(oldAutoCommit);
            } catch (SQLException ignored) {
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        flushAsync();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
        }
    }
}
