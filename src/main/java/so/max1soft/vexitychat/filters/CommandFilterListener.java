package so.max1soft.vexitychat.filters;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class CommandFilterListener implements Listener {

    private final ChatFilter chatFilter;
    private final ChatDelay chatDelay;
    private final List<String> commandPrefixes;
    private final List<String> newbieCommands;
    private final long newbieDelay;
    private final String newbieDelayMessage;
    private final JavaPlugin plugin;

    public CommandFilterListener(ChatFilter chatFilter, ChatDelay chatDelay, JavaPlugin plugin) {
        this.chatFilter = chatFilter;
        this.chatDelay = chatDelay;
        this.plugin = plugin;
        this.newbieCommands = plugin.getConfig().getStringList("newbie-commands.list");
        this.newbieDelay = plugin.getConfig().getLong("newbie-commands.delay", 300);
        this.newbieDelayMessage = plugin.getConfig().getString("messages.chat-delay-message", "");
        // Все варианты команд личных сообщений (включая CMI и другие плагины)
        this.commandPrefixes = List.of(
            "/m ", "/msg ", "/r ", "/reply ", "/tell ", "/w ", "/whisper ",
            "/pm ", "/privatemessage ",
            "/cmi msg ", "/cmi m ", "/cmi r ", "/cmi reply ", "/cmi tell ", "/cmi w ",
            "/cmi:msg ", "/cmi:m ", "/cmi:r ", "/cmi:reply ", "/cmi:tell ", "/cmi:w ",
            "/bc ", "/broadcast ",
            "/cmi bc ", "/cmi broadcast ",
            "/cmi:bc ", "/cmi:broadcast "
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        String command = event.getMessage();
        boolean debug = plugin.getConfig().getBoolean("chat.debug", false);

        if (debug) plugin.getLogger().info("[ChatDebug] Command: " + command + " | player: " + player.getName() + " | canChat: " + chatDelay.canChat(player));

        // Проверка newbie-commands
        if (!chatDelay.canChat(player)) {
            for (String newbieCmd : newbieCommands) {
                if (commandMatchesNewbie(command, newbieCmd)) {
                    long playedSeconds = chatDelay.getPlayerPlaytime(player);
                    if (debug) plugin.getLogger().info("[ChatDebug] Newbie cmd match: " + newbieCmd + " | played: " + playedSeconds + " | required: " + newbieDelay);
                    if (playedSeconds < newbieDelay) {
                        if (!newbieDelayMessage.isEmpty()) {
                            long remaining = newbieDelay - playedSeconds;
                            player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                                    newbieDelayMessage.replace("{MINUTES}", formatTime(remaining))));
                        }
                        event.setCancelled(true);
                        return;
                    }
                }
            }
        }

        // Проверяем каждый префикс команды
        for (String prefix : commandPrefixes) {
            if (command.toLowerCase().startsWith(prefix.toLowerCase())) {
                // Извлекаем аргументы после команды (пропускаем имя игрока для msg команд)
                String args = command.substring(prefix.length()).trim();
                
                if (args.isEmpty()) {
                    return;
                }

                String messageToFilter;
                
                // Для команд msg/m/tell/w/pm - первый аргумент это ник, фильтруем остальное
                if (prefix.contains("msg") || prefix.contains("/m ") || prefix.contains("tell") || 
                    prefix.contains("/w ") || prefix.contains("whisper") || prefix.contains("pm") ||
                    prefix.contains("privatemessage")) {
                    
                    String[] parts = args.split(" ", 2);
                    if (parts.length < 2) {
                        return; // Нет сообщения, только ник
                    }
                    messageToFilter = parts[1];
                } else if (prefix.contains("/r ") || prefix.contains("reply")) {
                    // Для reply - всё сообщение
                    messageToFilter = args;
                } else {
                    // Для broadcast и других - всё сообщение
                    messageToFilter = args;
                }

                // Применяем фильтр
                String filteredMessage = chatFilter.filterMessage(player, messageToFilter);

                if (filteredMessage == null) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "Сообщение содержит запрещенные слова или символы.");
                    return;
                }

                if (!filteredMessage.equals(messageToFilter)) {
                    // Сообщение было изменено фильтром - заменяем команду
                    String newCommand;
                    if (prefix.contains("msg") || prefix.contains("/m ") || prefix.contains("tell") || 
                        prefix.contains("/w ") || prefix.contains("whisper") || prefix.contains("pm") ||
                        prefix.contains("privatemessage")) {
                        
                        String[] parts = args.split(" ", 2);
                        newCommand = prefix + parts[0] + " " + filteredMessage;
                    } else {
                        newCommand = prefix + filteredMessage;
                    }
                    event.setMessage(newCommand);
                }
                return;
            }
        }
    }
    private boolean commandMatchesNewbie(String command, String newbieCmd) {
        String cmd = command.toLowerCase();
        String target = newbieCmd.toLowerCase();
        // Точное совпадение или команда с аргументами (target + пробел)
        return cmd.equals(target) || cmd.startsWith(target + " ");
    }

    private String formatTime(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder formattedTime = new StringBuilder();
        if (hours > 0) {
            formattedTime.append(hours).append(" ч. ");
        }
        if (minutes > 0 || hours > 0) {
            formattedTime.append(minutes).append(" м. ");
        }
        formattedTime.append(seconds).append(" сек.");

        return formattedTime.toString().trim();
    }
}
