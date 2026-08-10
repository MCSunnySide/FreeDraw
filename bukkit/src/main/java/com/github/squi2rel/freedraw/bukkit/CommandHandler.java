package com.github.squi2rel.freedraw.bukkit;

import com.github.squi2rel.freedraw.bukkit.brush.BrushPath;
import com.github.squi2rel.freedraw.bukkit.network.PacketHandler;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
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

/**
 * /drawcolor - set your drawing color (mirrors the Fabric mod's command).
 * /freedraw   - admin commands: reload, save, clear.
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
                sender.sendMessage(ChatColor.GREEN + "Cleared " + old.size() + " paths.");
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Usage: /freedraw <reload|save|clear [confirm]>");
            }
        }
        return true;
    }

    // --- helpers ---

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
                for (String sub : new String[]{"reload", "save", "clear"}) {
                    if (sub.startsWith(prefix)) suggestions.add(sub);
                }
            } else if (args.length == 2 && args[0].equalsIgnoreCase("clear")) {
                suggestions.add("confirm");
            }
        }
        return suggestions;
    }
}
