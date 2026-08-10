package com.github.squi2rel.freedraw.bukkit;

import com.github.squi2rel.freedraw.bukkit.brush.BrushPath;
import com.github.squi2rel.freedraw.bukkit.network.PacketHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks which paths each player can currently see, sending CREATE_PATH/ADD_POINTS
 * when a path enters the player's range and REMOVE_PATH when it leaves.
 * Mirrors {@code com.github.squi2rel.freedraw.RegionManager}.
 */
public class RegionManager {
    private final int boxSize;
    private final Map<String, SpatialHash> worlds = new HashMap<>();
    private final Map<UUID, State> playerStates = new HashMap<>();

    public RegionManager(int boxSize) {
        this.boxSize = boxSize;
    }

    public void insert(BrushPath path) {
        worlds.computeIfAbsent(path.world, k -> new SpatialHash(boxSize)).insert(path);
    }

    public void remove(BrushPath path) {
        SpatialHash box = worlds.get(path.world);
        if (box == null) return;
        box.remove(path);
        if (box.isEmpty()) worlds.remove(path.world);
        for (Map.Entry<UUID, State> entry : playerStates.entrySet()) {
            if (entry.getValue().paths.contains(path)) {
                remove(entry.getKey(), path);
            }
        }
    }

    public void removePlayer(UUID uuid) {
        playerStates.remove(uuid);
    }

    public void update(UUID player, String world, double px, double py, double pz) {
        SpatialHash box = worlds.get(world);
        if (box == null) return;

        State state = playerStates.get(player);
        if (state == null) {
            state = new State();
            state.world = world;
            playerStates.put(player, state);
        }

        Set<BrushPath> current = box.get(px, py, pz, DataHolder.config.broadcastRange);
        if (Objects.equals(state.world, world)) {
            Set<BrushPath> entered = new HashSet<>(current);
            entered.removeAll(state.paths);
            for (BrushPath path : entered) {
                create(player, path);
            }

            List<BrushPath> exited = new ArrayList<>(state.paths);
            exited.removeAll(current);
            for (BrushPath path : exited) {
                remove(player, path);
            }
        } else {
            for (BrushPath path : current) {
                create(player, path);
            }
        }

        state.world = world;
        state.paths = new HashSet<>(current);
    }

    private void create(UUID player, BrushPath path) {
        Player p = Bukkit.getPlayer(player);
        if (p == null || !p.isOnline()) return;
        PacketHandler.sendTo(p, PacketHandler.createPath(path));
        PacketHandler.sendTo(p, PacketHandler.addPoints(path));
    }

    private void remove(UUID player, BrushPath path) {
        Player p = Bukkit.getPlayer(player);
        if (p == null || !p.isOnline()) return;
        PacketHandler.sendTo(p, PacketHandler.removePath(path.uuid));
    }

    private static class State {
        String world;
        Set<BrushPath> paths = new HashSet<>();
    }
}
