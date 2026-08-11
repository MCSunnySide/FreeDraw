package com.github.squi2rel.freedraw.bukkit;

import com.github.squi2rel.freedraw.bukkit.brush.BrushPath;
import com.github.squi2rel.freedraw.bukkit.database.ActionLog;
import com.github.squi2rel.freedraw.bukkit.network.PacketHandler;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * /drawcolor - set your drawing color (mirrors the Fabric mod's command).
 * /freedraw   - admin commands: reload, save, clear, rollback.
 */
public class CommandHandler implements CommandExecutor, TabCompleter {
    public static final CommandHandler INSTANCE = new CommandHandler();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("drawcolor")) {
            return drawColor(sender, args);
        }
        if (command.getName().equalsIgnoreCase("freedraw")) {
            return freedraw(sender, args);
        }
        return false;
    }

    private boolean drawColor(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return true;
        }
        if (args.length < 1) return false;

        String str = args[0].toLowerCase(Locale.ROOT);
        int color;
        if (str.equals("rainbow")) {
            PacketHandler.sendTo(player, PacketHandler.color(0));
            player.sendMessage(rainbowText("Current color: RAINBOW"));
            return true;
        }
        DyeColor dyeColor = getDyeColor(str);
        if (dyeColor != null) {
            color = dyeColor.getColor().asRGB() | 0xFF000000;
        } else {
            try {
                color = parseColor(str);
            } catch (IllegalArgumentException e) {
                player.sendMessage(ChatColor.RED + "Invalid color");
                return true;
            }
        }
        PacketHandler.sendTo(player, PacketHandler.color(color));
        String hex = String.format("#%06X", color & 0xFFFFFF);
        player.sendMessage(ChatColor.of(hex) + "Current color: " + hex);
        return true;
    }

    private boolean freedraw(CommandSender sender, String[] args) {
        if (args.length < 1) return false;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                DataHolder.load();
                sender.sendMessage(ChatColor.GREEN + "FreeDraw config reloaded.");
            }
            case "save" -> {
                DataHolder.save();
                sender.sendMessage(ChatColor.GREEN + "FreeDraw data saved.");
            }
            case "clear" -> {
                if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                    sender.sendMessage(ChatColor.RED + "This will delete ALL drawings. Run /freedraw clear confirm to proceed.");
                    return true;
                }
                Map<UUID, BrushPath> old = DataHolder.config.paths;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    for (UUID uuid : new ArrayList<>(old.keySet())) {
                        PacketHandler.sendTo(p, PacketHandler.removePath(uuid));
                    }
                }
                DataHolder.config.paths = new HashMap<>();
                DataHolder.paths = new RegionManager(DataHolder.config.broadcastRange);
                DataHolder.players.clear();
                // Async: remove all paths from storage.
                for (UUID uuid : old.keySet()) {
                    DataHolder.db.deletePath(uuid);
                }
                sender.sendMessage(ChatColor.GREEN + "Cleared " + old.size() + " paths.");
            }
            case "rollback" -> rollback(sender, args);
            case "lookup" -> lookup(sender, args);
            default -> sender.sendMessage(ChatColor.RED + "Usage: /freedraw <reload|save|clear [confirm]|rollback [player] [time] [radius]|lookup [player] [time] [radius]>");
        }
        return true;
    }

    // --- rollback (CoreProtect-style) ---

    /**
     * Usage:
     *   /freedraw rollback [player] [time] [radius]
     * e.g. /freedraw rollback uoqoerhew 2h 50  - undo uoqoerhew's actions in the last 2h within 50 blocks
     *      /freedraw rollback 30m              - undo everyone's actions in the last 30 minutes (no radius)
     *      /freedraw rollback 1d 100           - undo everyone's actions in the last day within 100 blocks of self
     *
     * DRAW  actions are undone by deleting the path.
     * ERASE actions are undone by restoring the path (full data was stored).
     */
    private void rollback(CommandSender sender, String[] args) {
        UUID targetPlayer = null;
        long sinceTs = 0;
        double radius = Double.NaN;
        double centerX = 0, centerZ = 0;

        // Parse flexible args: optional player name first, then optional time, then optional radius.
        List<String> rest = new ArrayList<>();
        int argIdx = 1;
        boolean playerMatched = false;
        while (argIdx < args.length) {
            String arg = args[argIdx];
            if (!playerMatched && arg.length() <= 16 && isPlayerName(arg)) {
                @SuppressWarnings("deprecation")
                org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(arg);
                targetPlayer = op.getUniqueId();
                playerMatched = true;
            } else if (sinceTs == 0 && isTime(arg)) {
                sinceTs = parseTime(arg);
            } else if (Double.isNaN(radius) && isRadius(arg)) {
                radius = Double.parseDouble(arg);
            } else {
                rest.add(arg);
            }
            argIdx++;
        }

        if (sender instanceof Player p) {
            centerX = p.getLocation().getX();
            centerZ = p.getLocation().getZ();
        }
        World world = sender instanceof Player p2 ? p2.getWorld() : null;

        sender.sendMessage(ChatColor.YELLOW + "Querying FreeDraw action log...");

        CompletableFuture<List<ActionLog>> future = DataHolder.db.queryActions(
                targetPlayer, sinceTs, world != null ? world.getName() : null, centerX, centerZ, radius);

        future.whenComplete((logs, err) -> {
            if (err != null) {
                sender.sendMessage(ChatColor.RED + "Rollback query failed: " + err.getMessage());
                return;
            }
            if (logs == null || logs.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "No matching actions found.");
                return;
            }
            // Apply on the main thread.
            Bukkit.getScheduler().runTask(FreeDrawPlugin.instance, () -> applyRollback(sender, logs));
        });
    }

    private void applyRollback(CommandSender sender, List<ActionLog> logs) {
        int undone = 0;
        List<Long> ids = new ArrayList<>();
        for (ActionLog log : logs) {
            try {
                if (log.type == ActionLog.Type.DRAW) {
                    // Undo a draw: delete the path everywhere.
                    BrushPath path = DataHolder.config.paths.remove(log.pathUuid);
                    if (path != null) {
                        DataHolder.paths.remove(path);
                        broadcastRemove(path);
                    }
                    DataHolder.db.deletePath(log.pathUuid);
                } else {
                    // Undo an erase: restore the path from stored data.
                    if (log.pathData == null) continue;
                    BrushPath path = com.github.squi2rel.freedraw.bukkit.util.PathCodec.decode(log.pathUuid, log.pathData);
                    DataHolder.config.paths.put(path.uuid, path);
                    DataHolder.paths.insert(path);
                    DataHolder.db.savePath(path);
                    broadcastCreate(path);
                }
                ids.add(log.id);
                undone++;
            } catch (Exception e) {
                FreeDrawPlugin.LOGGER.warning("Rollback action " + log.id + " failed: " + e.getMessage());
            }
        }
        if (!ids.isEmpty()) {
            DataHolder.db.markRolledBack(ids);
        }
        sender.sendMessage(ChatColor.GREEN + "Rolled back " + undone + " actions (" + logs.size() + " matched).");
    }

    private void broadcastRemove(BrushPath path) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().getName().equals(path.world)) continue;
            PacketHandler.sendTo(p, PacketHandler.removePath(path.uuid));
        }
    }

    private void broadcastCreate(BrushPath path) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().getName().equals(path.world)) continue;
            Location loc = p.getLocation();
            double dist = loc.distanceSquared(new Location(loc.getWorld(), path.offset.x, path.offset.y, path.offset.z));
            if (dist <= (double) DataHolder.config.broadcastRange * DataHolder.config.broadcastRange) {
                PacketHandler.sendTo(p, PacketHandler.createPath(path));
                PacketHandler.sendTo(p, PacketHandler.addPoints(path));
            }
        }
    }

    // --- lookup (CoreProtect-style read-only query) ---

    /**
     * Usage (same filters as rollback, but read-only):
     *   /freedraw lookup [player] [time] [radius]
     * e.g. /freedraw lookup uoqoerhew 2h 50
     *      /freedraw lookup 30m
     */
    private void lookup(CommandSender sender, String[] args) {
        UUID targetPlayer = null;
        long sinceTs = 0;
        double radius = Double.NaN;
        double centerX = 0, centerZ = 0;

        int argIdx = 1;
        boolean playerMatched = false;
        while (argIdx < args.length) {
            String arg = args[argIdx];
            if (!playerMatched && arg.length() <= 16 && isPlayerName(arg)) {
                @SuppressWarnings("deprecation")
                org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(arg);
                targetPlayer = op.getUniqueId();
                playerMatched = true;
            } else if (sinceTs == 0 && isTime(arg)) {
                sinceTs = parseTime(arg);
            } else if (Double.isNaN(radius) && isRadius(arg)) {
                radius = Double.parseDouble(arg);
            }
            argIdx++;
        }

        if (sender instanceof Player p) {
            centerX = p.getLocation().getX();
            centerZ = p.getLocation().getZ();
        }
        World world = sender instanceof Player p2 ? p2.getWorld() : null;

        sender.sendMessage(ChatColor.YELLOW + "Querying FreeDraw action log...");

        DataHolder.db.queryActions(targetPlayer, sinceTs, world != null ? world.getName() : null, centerX, centerZ, radius)
                .whenComplete((logs, err) -> {
                    if (err != null) {
                        sender.sendMessage(ChatColor.RED + "Lookup failed: " + err.getMessage());
                        return;
                    }
                    if (logs == null || logs.isEmpty()) {
                        sender.sendMessage(ChatColor.RED + "No matching actions found.");
                        return;
                    }
                    int limit = Math.min(logs.size(), 20);
                    sender.sendMessage(ChatColor.GOLD + "FreeDraw actions (showing " + limit + "/" + logs.size() + "):");
                    for (int i = 0; i < limit; i++) {
                        ActionLog log = logs.get(i);
                        String time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(log.timestamp));
                        String player = log.playerName != null ? log.playerName : (log.playerUuid != null ? log.playerUuid.toString().substring(0, 8) : "?");
                        String action = log.type == ActionLog.Type.DRAW ? ChatColor.GREEN + "draw" : ChatColor.RED + "erase";
                        sender.sendMessage(ChatColor.GRAY + "#" + log.id + " " + time
                                + ChatColor.WHITE + " " + player
                                + " " + action
                                + ChatColor.WHITE + " in " + log.world
                                + " (" + log.pointCount + " pts, " + String.format("#%06X", log.color & 0xFFFFFF) + ")");
                    }
                });
    }

    // --- arg parsing helpers ---

    private static boolean isPlayerName(String arg) {
        return Bukkit.getPlayerExact(arg) != null || Bukkit.getOfflinePlayer(arg).hasPlayedBefore();
    }

    private static boolean isTime(String arg) {
        if (arg.startsWith("#")) return arg.length() > 1 && arg.substring(1).chars().allMatch(Character::isDigit);
        if (!arg.matches("\\d+[smhd]")) return false;
        return true;
    }

    private static long parseTime(String arg) {
        long now = System.currentTimeMillis();
        if (arg.startsWith("#")) return now - 0; // id-based not used here
        long value = Long.parseLong(arg.substring(0, arg.length() - 1));
        return switch (arg.charAt(arg.length() - 1)) {
            case 's' -> now - value * 1000L;
            case 'm' -> now - value * 60_000L;
            case 'h' -> now - value * 3_600_000L;
            case 'd' -> now - value * 86_400_000L;
            default -> now;
        };
    }

    private static boolean isRadius(String arg) {
        return arg.matches("\\d{1,4}");
    }

    // --- existing helpers ---

    private static DyeColor getDyeColor(String name) {
        for (DyeColor dye : DyeColor.values()) {
            if (dye.name().equalsIgnoreCase(name)) return dye;
        }
        return null;
    }

    private static String rainbowText(String content) {
        StringBuilder sb = new StringBuilder();
        int length = content.length();
        for (int i = 0; i < length; i++) {
            float hue = (float) i / length;
            int rgb = hsbToRgb(hue, 0.8f, 1.0f) & 0xFFFFFF;
            sb.append(ChatColor.of(String.format("#%06X", rgb))).append(content.charAt(i));
        }
        return sb.toString();
    }

    private static int hsbToRgb(float h, float s, float b) {
        int r = 0, g = 0, b_ = 0;
        if (s == 0) {
            r = g = b_ = (int) (b * 255.0f + 0.5f);
        } else {
            float h_ = (h - (float) Math.floor(h)) * 6.0f;
            float f = h_ - (float) Math.floor(h_);
            float p = b * (1.0f - s);
            float q = b * (1.0f - s * f);
            float t = b * (1.0f - (s * (1.0f - f)));
            switch ((int) h_) {
                case 0 -> { r = (int) (b * 255.0f + 0.5f); g = (int) (t * 255.0f + 0.5f); b_ = (int) (p * 255.0f + 0.5f); }
                case 1 -> { r = (int) (q * 255.0f + 0.5f); g = (int) (b * 255.0f + 0.5f); b_ = (int) (p * 255.0f + 0.5f); }
                case 2 -> { r = (int) (p * 255.0f + 0.5f); g = (int) (b * 255.0f + 0.5f); b_ = (int) (t * 255.0f + 0.5f); }
                case 3 -> { r = (int) (p * 255.0f + 0.5f); g = (int) (q * 255.0f + 0.5f); b_ = (int) (b * 255.0f + 0.5f); }
                case 4 -> { r = (int) (t * 255.0f + 0.5f); g = (int) (p * 255.0f + 0.5f); b_ = (int) (b * 255.0f + 0.5f); }
                case 5 -> { r = (int) (b * 255.0f + 0.5f); g = (int) (p * 255.0f + 0.5f); b_ = (int) (q * 255.0f + 0.5f); }
            }
        }
        return 0xFF000000 | (r << 16) | (g << 8) | b_;
    }

    private static int parseColor(String colorStr) {
        String hex = colorStr.startsWith("#") ? colorStr.substring(1) : colorStr;
        if (hex.length() != 6 && hex.length() != 8) throw new IllegalArgumentException("Invalid color");
        try {
            int r = Integer.parseInt(hex.substring(0, 2), 16);
            int g = Integer.parseInt(hex.substring(2, 4), 16);
            int b = Integer.parseInt(hex.substring(4, 6), 16);
            int a = 0xFF;
            if (hex.length() == 8) a = Integer.parseInt(hex.substring(6, 8), 16);
            return (a << 24) | (r << 16) | (g << 8) | b;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid color", e);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("drawcolor") && args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            if ("rainbow".startsWith(prefix)) suggestions.add("rainbow");
            for (DyeColor dye : DyeColor.values()) {
                String name = dye.name().toLowerCase(Locale.ROOT);
                if (name.startsWith(prefix)) suggestions.add(name);
            }
        } else if (command.getName().equalsIgnoreCase("freedraw")) {
            if (args.length == 1) {
                String prefix = args[0].toLowerCase(Locale.ROOT);
                for (String sub : new String[]{"reload", "save", "clear", "rollback", "lookup"}) {
                    if (sub.startsWith(prefix)) suggestions.add(sub);
                }
            } else if (args.length == 2 && args[0].equalsIgnoreCase("clear")) {
                suggestions.add("confirm");
            } else if (args.length >= 2 && (args[0].equalsIgnoreCase("rollback") || args[0].equalsIgnoreCase("lookup"))) {
                completeRollbackArgs(sender, args, suggestions);
            }
        }
        return suggestions;
    }

    /**
     * Tab completion for {@code /freedraw rollback|lookup [player] [time] [radius]}.
     * The three parameters can be given in any order; we track which kinds have
     * already been supplied and only suggest the remaining kinds.
     */
    private void completeRollbackArgs(CommandSender sender, String[] args, List<String> suggestions) {
        boolean hasPlayer = false;
        boolean hasTime = false;
        boolean hasRadius = false;
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);

        // Classify the already-typed parameters (excluding the one being completed).
        for (int i = 1; i < args.length - 1; i++) {
            String arg = args[i];
            if (!hasPlayer && isPlayerName(arg)) hasPlayer = true;
            else if (!hasTime && isTime(arg)) hasTime = true;
            else if (!hasRadius && isRadius(arg)) hasRadius = true;
        }

        // Player names (only if a player param hasn't been supplied yet).
        if (!hasPlayer) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) suggestions.add(p.getName());
            }
        }

        // Time values (only if a time param hasn't been supplied yet).
        if (!hasTime) {
            for (String t : new String[]{"30s", "10m", "1h", "6h", "24h", "7d"}) {
                if (t.startsWith(prefix)) suggestions.add(t);
            }
        }

        // Radius (only if a radius param hasn't been supplied yet).
        if (!hasRadius) {
            for (String r : new String[]{"25", "50", "100", "200"}) {
                if (r.startsWith(prefix)) suggestions.add(r);
            }
        }
    }
}
