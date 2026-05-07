package so.max1soft.vexitychat.filters;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatFilter {

    private final String chatDelayMessage;
    private final String advancedDelayMessage;
    private final ChatDelay chatDelay;
    private final JavaPlugin plugin;
    private final boolean debug;
    private final boolean linksEnabled;
    private final List<String> linkWhitelist;
    private final String linkMessage;
    // Скомпилированные паттерны — один раз при создании
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)(https?://)?([\\w-]+\\.)+[\\w-]+(/[\\w-./?%&=]*)?");
    private static final Pattern CLICKABLE_URL_PATTERN = Pattern.compile("(https?://\\S+)");

    public ChatFilter(JavaPlugin plugin, FileConfiguration config, ChatDelay chatDelay) {
        this.plugin = plugin;
        this.chatDelayMessage = config.getString("messages.chat-delay-message", "");
        this.advancedDelayMessage = config.getString("messages.advanced-delay-message", "");
        this.chatDelay = chatDelay;
        this.debug = config.getBoolean("chat.debug", false);
        this.linksEnabled = config.getBoolean("links.enabled", false);
        this.linkWhitelist = config.getStringList("links.whitelist");
        this.linkMessage = config.getString("links.message", "");
    }

    public ChatDelay getChatDelay() {
        return chatDelay;
    }

    public String filterMessage(Player player, String message) {
        if (player == null) {
            plugin.getLogger().warning("Игрок равен null в методе filterMessage.");
            return null;
        }

        boolean debug = this.debug;
        if (debug) plugin.getLogger().info("[ChatDebug] filterMessage: '" + message + "' | bypass: " + player.hasPermission("vexity.bypass") + " | canChat: " + chatDelay.canChat(player));

        if (player.hasPermission("vexity.bypass")) return message;
        if (message.startsWith("/")) return message;

        if (!chatDelay.canChat(player)) {
            long chatDelayTime = chatDelay.getChatDelayTime();
            if (debug) plugin.getLogger().info("[ChatDebug] Blocked by canChat, delay: " + chatDelayTime);
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
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        advancedDelayMessage.replace("{TIME}", formatTime(remainingTime))));
                return null;
            }
        }

        if (!isLinkAllowed(player, message)) {
            if (!linkMessage.isEmpty()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', linkMessage));
            }
            return null;
        }

        String filteredMessage = filterAllowedSymbols(message, allowedSymbols);
        sendClickableMessage(player, filteredMessage);
        return filteredMessage;
    }

    private boolean isLinkAllowed(Player player, String message) {
        if (player.hasPermission("vexity.bypass")) return true;
        if (!linksEnabled) return true;

        Matcher matcher = URL_PATTERN.matcher(message);
        while (matcher.find()) {
            String url = matcher.group().toLowerCase();
            boolean whitelisted = linkWhitelist.stream().anyMatch(a -> url.contains(a.toLowerCase()));
            if (!whitelisted) return false;
        }
        return true;
    }

    private void sendClickableMessage(Player player, String message) {
        Matcher matcher = CLICKABLE_URL_PATTERN.matcher(message);
        TextComponent textComponent = new TextComponent("");
        int lastMatchEnd = 0;

        while (matcher.find()) {
            if (matcher.start() > lastMatchEnd) {
                textComponent.addExtra(new TextComponent(message.substring(lastMatchEnd, matcher.start())));
            }
            String url = matcher.group();
            TextComponent linkComponent = new TextComponent(url);
            linkComponent.setColor(net.md_5.bungee.api.ChatColor.BLUE);
            linkComponent.setUnderlined(true);
            linkComponent.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
            textComponent.addExtra(linkComponent);
            lastMatchEnd = matcher.end();
        }
        if (lastMatchEnd < message.length()) {
            textComponent.addExtra(new TextComponent(message.substring(lastMatchEnd)));
        }
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
