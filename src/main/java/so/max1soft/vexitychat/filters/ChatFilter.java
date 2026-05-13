package so.max1soft.vexitychat.filters;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatFilter {

    private final ChatDelay chatDelay;
    private final JavaPlugin plugin;
    // Скомпилированные паттерны — один раз при создании
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)(https?://)?([\\w-]+\\.)+[\\w-]+(/[\\w-./?%&=]*)?");

    public ChatFilter(JavaPlugin plugin, ChatDelay chatDelay) {
        this.plugin = plugin;
        this.chatDelay = chatDelay;
    }

    public ChatDelay getChatDelay() {
        return chatDelay;
    }

    public boolean isLinkAllowedForChat(Player player, String message) {
        if (player == null) {
            plugin.getLogger().warning("Игрок равен null в методе isLinkAllowedForChat.");
            return false;
        }

        if (player.hasPermission("vexity.bypass") || message.startsWith("/")) return true;

        if (!isLinkAllowed(player, message)) {
            String linkMessage = plugin.getConfig().getString("links.message", "");
            if (!linkMessage.isEmpty()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', linkMessage));
            }
            return false;
        }

        return true;
    }

    public String filterMessage(Player player, String message) {
        if (player == null) {
            plugin.getLogger().warning("Игрок равен null в методе filterMessage.");
            return null;
        }

        boolean debug = plugin.getConfig().getBoolean("chat.debug", false);
        boolean bypass = player.hasPermission("vexity.bypass");
        boolean canChat = bypass || chatDelay.canChat(player);
        if (debug) plugin.getLogger().info("[ChatDebug] filterMessage: '" + message + "' | bypass: " + bypass + " | canChat: " + canChat);

        if (bypass) return message;
        if (message.startsWith("/")) return message;

        if (!canChat) {
            long chatDelayTime = chatDelay.getChatDelayTime();
            if (debug) plugin.getLogger().info("[ChatDebug] Blocked by canChat, delay: " + chatDelayTime);
            String chatDelayMessage = plugin.getConfig().getString("messages.chat-delay-message", "");
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    chatDelayMessage.replace("{MINUTES}", formatTime(chatDelayTime))));
            return null;
        }

        // Получаем плейтайм один раз для всех проверок
        long playtime = chatDelay.getPlayerPlaytime(player);
        String allowedSymbols = chatDelay.getAllowedSymbolsForPlayer(playtime);
        boolean hasDisallowedSymbols = message.chars().anyMatch(c -> allowedSymbols.indexOf(c) < 0);

        if (hasDisallowedSymbols) {
            long remainingTime = chatDelay.getADelayTime() - playtime;
            if (remainingTime > 0) {
                String advancedDelayMessage = plugin.getConfig().getString("messages.advanced-delay-message", "");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        advancedDelayMessage.replace("{TIME}", formatTime(remainingTime))));
                return null;
            }
        }

        if (!isLinkAllowed(player, message)) {
            String linkMessage = plugin.getConfig().getString("links.message", "");
            if (!linkMessage.isEmpty()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', linkMessage));
            }
            return null;
        }

        String filteredMessage = filterAllowedSymbols(message, allowedSymbols);
        return filteredMessage;
    }

    private boolean isLinkAllowed(Player player, String message) {
        if (player.hasPermission("vexity.bypass")) return true;
        if (!plugin.getConfig().getBoolean("links.enabled", false)) return true;

        List<String> whitelist = plugin.getConfig().getStringList("links.whitelist");
        Matcher matcher = URL_PATTERN.matcher(message);
        while (matcher.find()) {
            String url = matcher.group().toLowerCase(Locale.ROOT);
            boolean whitelisted = false;
            for (String allowed : whitelist) {
                if (url.contains(allowed.toLowerCase(Locale.ROOT))) {
                    whitelisted = true;
                    break;
                }
            }
            if (!whitelisted) return false;
        }
        return true;
    }

    private String filterAllowedSymbols(String message, String allowedSymbols) {
        StringBuilder result = new StringBuilder();
        for (char c : message.toCharArray()) {
            result.append(allowedSymbols.indexOf(c) >= 0 ? c : '*');
        }
        return result.toString();
    }

    private String formatTime(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (hours > 0) sb.append(hours).append(" ч. ");
        if (minutes > 0 || hours > 0) sb.append(minutes).append(" м. ");
        sb.append(seconds).append(" сек.");
        return sb.toString().trim();
    }
}
