package so.max1soft.vexitychat.listeners;

import me.clip.placeholderapi.PlaceholderAPI;
import net.luckperms.api.LuckPerms;
import org.bukkit.Bukkit;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.regex.Pattern;

public class ChatConstructor {

    private static final Pattern ANIMATION_PATTERN = Pattern.compile("%animation:([^%]+)%");

    private final JavaPlugin plugin;
    private final LuckPerms luckPerms;
    private final boolean papiAvailable;

    public ChatConstructor(JavaPlugin plugin, LuckPerms luckPerms) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
        this.papiAvailable = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    public String constructLocalMessage(Player player, String message) {
        String format = plugin.getConfig().getString("chat.local");
        return replacePlaceholders(format, player, message);
    }

    public String constructGlobalMessage(Player player, String message) {
        String format = plugin.getConfig().getString("chat.global");
        return replacePlaceholders(format, player, message);
    }

    public int getBlockRadius() {
        return plugin.getConfig().getInt("chat.block-radius");
    }
    private String replacePlaceholders(String format, Player player, String message) {
        if (format == null) format = "";
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        String prefix = user != null ? user.getCachedData().getMetaData().getPrefix() : "";
        String suffix = user != null ? user.getCachedData().getMetaData().getSuffix() : "";

        format = format
                .replace("{PLAYER}", "<<PLAYER>>")
                .replace("{MESSAGE}", message)
                .replace("{PREFIX}", prefix != null ? prefix : "")
                .replace("{SUFFIX}", suffix != null ? suffix : "");

        // Автоматическая замена %animation:name% на PAPI-совместимый %tab_placeholder_animation:name%
        format = ANIMATION_PATTERN.matcher(format).replaceAll("%tab_placeholder_animation:$1%");

        if (papiAvailable) {
            format = PlaceholderAPI.setPlaceholders(player, format);
        }

        return format;
    }

}
