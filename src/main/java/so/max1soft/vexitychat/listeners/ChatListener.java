package so.max1soft.vexitychat.listeners;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.md_5.bungee.api.chat.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;
import so.max1soft.vexitychat.filters.ChatFilter;
import so.max1soft.vexitychat.managers.VexityAIManager;
import so.max1soft.vexitychat.utils.ColorUtil;
import me.clip.placeholderapi.PlaceholderAPI;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatListener implements Listener {

    private static final Pattern CLICKABLE_URL_PATTERN = Pattern.compile("(https?://\\S+)");

    private final ChatConstructor chatConstructor;
    private final ChatFilter chatFilter;
    private final Map<UUID, Long> lastMessageTime;
    private final LuckPerms luckPerms;
    private final Plugin plugin;
    private final VexityAIManager aiManager;

    public ChatListener(ChatConstructor chatConstructor, ChatFilter chatFilter, LuckPerms luckPerms, Plugin plugin, VexityAIManager aiManager) {
        this.chatConstructor = chatConstructor;
        this.chatFilter = chatFilter;
        this.luckPerms = luckPerms;
        this.plugin = plugin;
        this.aiManager = aiManager;
        this.lastMessageTime = new ConcurrentHashMap<>();
    }

    // ============= Legacy (1.16 - 1.18) =============
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        handleChat(event.getPlayer(), event.getMessage(), () -> event.setCancelled(true), event::isCancelled);
    }

    // ============= Core logic =============
    void handleChat(Player player, String originalMessage, Runnable cancel, BooleanSupplier isCancelled) {
        if (originalMessage.startsWith("/")) return;

        boolean softMode = isSoftMode();
        if (!softMode && isCancelled.getAsBoolean()) return;

        if (softMode && isCancelled.getAsBoolean()) {
            aiManager.analyze(player, originalMessage);
            return;
        }

        if (softMode) {
            if (!chatFilter.isLinkAllowedForChat(player, originalMessage)) {
                cancel.run();
                return;
            }

            aiManager.analyze(player, originalMessage);
            if (plugin.getConfig().getBoolean("chat.debug", false)) {
                plugin.getLogger().info("[ChatDebug] SOFT mode passed chat event for " + player.getName());
            }
            return;
        }

        long currentTime = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();
        long lastTime = lastMessageTime.getOrDefault(playerId, 0L);
        long chatDelay = loadChatDelay(player);

        if (currentTime - lastTime < chatDelay) {
            long timeLeft = (chatDelay - (currentTime - lastTime)) / 1000;
            String delayMessage = ChatColor.translateAlternateColorCodes('&',
                    plugin.getConfig().getString("chat.delay-message", ""));
            player.sendMessage(delayMessage.replace("{TIME}", String.valueOf(timeLeft)));
            cancel.run();
            return;
        }

        if (!player.hasPermission("vexity.bypass")) {
            lastMessageTime.put(playerId, currentTime);
        }

        String filteredMessage = chatFilter.filterMessage(player, originalMessage);
        if (filteredMessage == null) {
            cancel.run();
            return;
        }

        aiManager.analyze(player, originalMessage);

        if (!player.hasPermission("vexity.color")) {
            filteredMessage = filteredMessage.replaceAll("(?i)&[0-9A-FK-OR]", "");
        }

        String mode = plugin.getConfig().getString("chat.mode", "DEFAULT").toUpperCase();
        String format;
        boolean isGlobal;

        if (mode.equals("RESTRICTED")) {
            isGlobal = true;
            String msg = filteredMessage.startsWith("!") ? filteredMessage.substring(1).trim() : filteredMessage;
            format = chatConstructor.constructGlobalMessage(player, msg);
        } else {
            isGlobal = filteredMessage.startsWith("!");
            format = isGlobal
                    ? chatConstructor.constructGlobalMessage(player, filteredMessage.substring(1).trim())
                    : chatConstructor.constructLocalMessage(player, filteredMessage);
        }

        String finalMessageStr = ColorUtil.translateColorCodes(format);
        logToConsole(player, originalMessage);
        cancel.run();

        if (isGlobal) {
            sendGlobalMessage(finalMessageStr, player);
        } else {
            sendHover(player, finalMessageStr, getPlayersInRadius(player));
        }
    }

    private long loadChatDelay(Player player) {
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) return 1;

        String highestGroup = null;
        int highestPriority = Integer.MIN_VALUE;
        ConfigurationSection groups = plugin.getConfig().getConfigurationSection("chat.delay.groups");
        if (groups == null) return 1;

        for (InheritanceNode node : user.getNodes(NodeType.INHERITANCE)) {
            String group = node.getGroupName();
            if (groups.contains(group)) {
                int priority = plugin.getConfig().getInt("chat.delay.priorities." + group, Integer.MAX_VALUE);
                if (priority > highestPriority) {
                    highestPriority = priority;
                    highestGroup = group;
                }
            }
        }

        if (highestGroup != null) {
            return plugin.getConfig().getLong("chat.delay.groups." + highestGroup) * 1000;
        }
        return 1;
    }

    private boolean isSoftMode() {
        return "SOFT".equalsIgnoreCase(plugin.getConfig().getString("chat.mode", "DEFAULT"));
    }

    private void sendGlobalMessage(String format, Player sender) {
        String[] parts = format.split("<<PLAYER>>", 2);
        TextComponent msg = buildMessage(
                parts.length > 0 ? parts[0] : "",
                parts.length > 1 ? parts[1] : "",
                sender);
        for (Player p : Bukkit.getOnlinePlayers()) p.spigot().sendMessage(msg);
    }

    private void sendHover(Player sender, String format, List<Player> recipients) {
        String[] parts = format.split("<<PLAYER>>", 2);
        TextComponent msg = buildMessage(
                parts.length > 0 ? parts[0] : "",
                parts.length > 1 ? parts[1] : "",
                sender);
        for (Player p : recipients) p.spigot().sendMessage(msg);
    }

    private TextComponent buildMessage(String before, String after, Player sender) {
        TextComponent result = new TextComponent();
        result.addExtra(new TextComponent(TextComponent.fromLegacyText(before)));

        TextComponent nameComp = new TextComponent(TextComponent.fromLegacyText(sender.getName()));
        if (plugin.getConfig().getBoolean("hover.enabled", true)) {
            nameComp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, getHoverComponents(sender)));
        }
        nameComp.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/msg " + sender.getName() + " "));
        result.addExtra(nameComp);
        result.addExtra(createMessageComponent(after));
        return result;
    }

    private TextComponent createMessageComponent(String message) {
        if (!plugin.getConfig().getBoolean("links.auto-style.enabled", true)) {
            return new TextComponent(TextComponent.fromLegacyText(message));
        }

        TextComponent comp = new TextComponent();
        Matcher matcher = CLICKABLE_URL_PATTERN.matcher(message);
        int last = 0;
        net.md_5.bungee.api.ChatColor linkColor = linkColor();
        boolean underline = plugin.getConfig().getBoolean("links.auto-style.underline", true);
        boolean openOnClick = plugin.getConfig().getBoolean("links.auto-style.open-on-click", true);

        while (matcher.find()) {
            if (matcher.start() > last) {
                comp.addExtra(new TextComponent(TextComponent.fromLegacyText(message.substring(last, matcher.start()))));
            }
            String url = matcher.group();
            TextComponent link = new TextComponent(url);
            if (linkColor != null) {
                link.setColor(linkColor);
            }
            link.setUnderlined(underline);
            if (openOnClick) {
                link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
            }
            comp.addExtra(link);
            last = matcher.end();
        }
        if (last < message.length()) {
            comp.addExtra(new TextComponent(TextComponent.fromLegacyText(message.substring(last))));
        }
        return comp;
    }

    private net.md_5.bungee.api.ChatColor linkColor() {
        String raw = plugin.getConfig().getString("links.auto-style.color", "RED");
        if (raw == null || raw.trim().isEmpty() || raw.equalsIgnoreCase("none")) {
            return null;
        }

        raw = raw.trim();
        if ((raw.startsWith("&") || raw.startsWith("§")) && raw.length() > 1) {
            net.md_5.bungee.api.ChatColor color = net.md_5.bungee.api.ChatColor.getByChar(raw.charAt(1));
            return color != null ? color : net.md_5.bungee.api.ChatColor.RED;
        }

        try {
            return net.md_5.bungee.api.ChatColor.valueOf(raw.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return net.md_5.bungee.api.ChatColor.RED;
        }
    }

    private BaseComponent[] getHoverComponents(Player player) {
        List<BaseComponent> components = new ArrayList<>();
        boolean papiAvailable = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        for (String line : plugin.getConfig().getStringList("hover.lines")) {
            String formatted = papiAvailable ? PlaceholderAPI.setPlaceholders(player, line) : line;
            formatted = ColorUtil.translateColorCodes(formatted);
            components.add(new TextComponent(formatted));
            components.add(new TextComponent("\n"));
        }
        if (!components.isEmpty()) components.remove(components.size() - 1);
        return components.toArray(new BaseComponent[0]);
    }

    private List<Player> getPlayersInRadius(Player sender) {
        int radius = chatConstructor.getBlockRadius();
        double radiusSquared = radius * (double) radius;
        Location senderLocation = sender.getLocation();
        List<Player> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(sender.getWorld()) &&
                p.getLocation().distanceSquared(senderLocation) <= radiusSquared) {
                list.add(p);
            }
        }
        return list;
    }

    private void logToConsole(Player player, String message) {
        Bukkit.getLogger().info("[Chat] <" + player.getName() + "> " + message);
    }
}
