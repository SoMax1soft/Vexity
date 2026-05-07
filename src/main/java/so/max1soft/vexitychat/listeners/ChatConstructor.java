package so.max1soft.vexitychat.listeners;

import me.clip.placeholderapi.PlaceholderAPI;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;

public class ChatConstructor {

    private final FileConfiguration config;
    private final LuckPerms luckPerms;

    public ChatConstructor(FileConfiguration config, LuckPerms luckPerms) {
        this.config = config;
        this.luckPerms = luckPerms;
    }

    public String constructLocalMessage(Player player, String message) {
        String format = config.getString("chat.local");
        return replacePlaceholders(format, player, message);
    }

    public String constructGlobalMessage(Player player, String message) {
        String format = config.getString("chat.global");
        return replacePlaceholders(format, player, message);
    }

    public int getBlockRadius() {
        return config.getInt("chat.block-radius");
    }
    private String replacePlaceholders(String format, Player player, String message) {
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        String prefix = user != null ? user.getCachedData().getMetaData().getPrefix() : "";
        String suffix = user != null ? user.getCachedData().getMetaData().getSuffix() : "";

        format = format
                .replace("{PLAYER}", "<<PLAYER>>")
                .replace("{MESSAGE}", message)
                .replace("{PREFIX}", prefix != null ? prefix : "")
                .replace("{SUFFIX}", suffix != null ? suffix : "");

        // Автоматическая замена %animation:name% на PAPI-совместимый %tab_placeholder_animation:name%
        format = format.replaceAll("%animation:([^%]+)%", "%tab_placeholder_animation:$1%");

        format = PlaceholderAPI.setPlaceholders(player, format);

        return format;
    }

}
