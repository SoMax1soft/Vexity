package so.max1soft.vexitychat.listeners;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.md_5.bungee.api.chat.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;
import so.max1soft.vexitychat.filters.ChatFilter;
import so.max1soft.vexitychat.managers.VexityAIManager;
import so.max1soft.vexitychat.utils.ColorUtil;
import me.clip.placeholderapi.PlaceholderAPI;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatListener implements Listener {

    private final ChatConstructor chatConstructor;
    private final ChatFilter chatFilter;
    private final List<String> hoverText;
    private final Map<Player, Long> lastMessageTime;
    private final String delayMessage;
    private final LuckPerms luckPerms;
    private final Plugin plugin;
    private final VexityAIManager aiManager;

    public ChatListener(ChatConstructor chatConstructor, ChatFilter chatFilter, List<String> hoverText, LuckPerms luckPerms, Plugin plugin, VexityAIManager aiManager) {
        this.chatConstructor = chatConstructor;
        this.chatFilter = chatFilter;
        this.hoverText = hoverText;
        this.luckPerms = luckPerms;
        this.plugin = plugin;
        this.aiManager = aiManager;
        FileConfiguration config = plugin.getConfig();
        this.lastMessageTime = new HashMap<>();
        this.delayMessage = ChatColor.translateAlternateColorCodes('&', config.getString("chat.delay-message", ""));
    }

    // ============= Legacy (1.16 - 1.18) =============
    @EventHandler(ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        handleChat(event.getPlayer(), event.getMessage(), () -> event.setCancelled(true));
    }

    // ============= Core logic =============
    void handleChat(Player player, String originalMessage, Runnable cancel) {
        if (originalMessage.startsWith("/")) return;

        long currentTime = System.currentTimeMillis();
        long lastTime = lastMessageTime.getOrDefault(player, 0L);
        long chatDelay = loadChatDelay(player);

        if (currentTime - lastTime < chatDelay) {
            long timeLeft = (chatDelay - (currentTime - lastTime)) / 1000;
            player.sendMessage(delayMessage.replace("{TIME}", String.valueOf(timeLeft)));
            cancel.run();
            return;
        }

        if (!player.hasPermission("vexity.bypass")) {
            lastMessageTime.put(player, currentTime);
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

        for (InheritanceNode node : user.getNodes(NodeType.INHERITANCE)) {
            String group = node.getGroupName();
            if (plugin.getConfig().getConfigurationSection("chat.delay.groups") != null &&
                plugin.getConfig().getConfigurationSection("chat.delay.groups").contains(group)) {
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
        TextComponent comp = new TextComponent();
        Pattern pattern = Pattern.compile("(https?://\\S+)");
        Matcher matcher = pattern.matcher(message);
        int last = 0;

        while (matcher.find()) {
            if (matcher.start() > last) {
                comp.addExtra(new TextComponent(TextComponent.fromLegacyText(message.substring(last, matcher.start()))));
            }
            String url = matcher.group();
            TextComponent link = new TextComponent(url);
            link.setColor(net.md_5.bungee.api.ChatColor.RED);
            link.setUnderlined(true);
            link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
            comp.addExtra(link);
            last = matcher.end();
        }
        if (last < message.length()) {
            comp.addExtra(new TextComponent(TextComponent.fromLegacyText(message.substring(last))));
        }
        return comp;
    }

    private BaseComponent[] getHoverComponents(Player player) {
        List<BaseComponent> components = new ArrayList<>();
        boolean papiAvailable = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        for (String line : hoverText) {
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
        List<Player> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(sender.getWorld()) &&
                p.getLocation().distance(sender.getLocation()) <= radius) {
                list.add(p);
            }
        }
        return list;
    }

    private void logToConsole(Player player, String message) {
        Bukkit.getLogger().info("[Chat] <" + player.getName() + "> " + message);
    }
}
