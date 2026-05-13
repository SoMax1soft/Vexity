package so.max1soft.vexitychat.commands;

import net.luckperms.api.node.Node;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;

public class CommandDelay implements Listener {

    private final JavaPlugin plugin;
    private final LuckPerms luckPerms;
    private final Map<String, Long> commandTimestamps = new HashMap<>();

    public CommandDelay(JavaPlugin plugin, LuckPerms luckPerms) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage().substring(1).toLowerCase(Locale.ROOT);
        FileConfiguration config = plugin.getConfig();


        org.bukkit.configuration.ConfigurationSection delaySection = config.getConfigurationSection("command-delay");
        if (delaySection == null) return;

        for (String configCommand : delaySection.getKeys(false)) {
            List<String> aliases = config.getStringList("command-delay." + configCommand + ".aliases");
            if (isCommandOrAlias(message, configCommand, aliases)) {

                long delay = getCooldownForPlayer(player, configCommand);
                String timestampKey = configCommand + ":" + player.getUniqueId();
                long lastExecuted = commandTimestamps.getOrDefault(timestampKey, 0L);
                long currentTime = System.currentTimeMillis();


                if (currentTime - lastExecuted < delay * 1000) {
                    long remainingTime = (delay * 1000 - (currentTime - lastExecuted)) / 1000;
                    String messageTemplate = config.getString("command-delay-message");
                    if (messageTemplate != null) {
                        String finalMessage = messageTemplate.replace("{TIME}", String.valueOf(remainingTime));
                        player.sendMessage(finalMessage);
                    } else {
                        player.sendMessage("Подождите " + remainingTime + " сек. перед повторным использованием команды.");
                    }
                    event.setCancelled(true);
                    return;
                } else {

                    commandTimestamps.put(timestampKey, currentTime);
                    break;
                }
            }
        }
    }

    private boolean isCommandOrAlias(String message, String command, List<String> aliases) {

        String normalizedCommand = command.toLowerCase(Locale.ROOT);
        if (message.equals(normalizedCommand) || message.startsWith(normalizedCommand + " ")) {
            return true;
        }
        for (String alias : aliases) {
            String normalizedAlias = alias.toLowerCase(Locale.ROOT);
            if (message.equals(normalizedAlias) || message.startsWith(normalizedAlias + " ")) {
                return true;
            }
        }
        return false;
    }

    private long getCooldownForPlayer(Player player, String command) {
        FileConfiguration config = plugin.getConfig();

        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return 1;
        }


        String group = getPlayerGroup(user);


        if (config.contains("command-delay." + command + ".cooldown." + group)) {
            return config.getLong("command-delay." + command + ".cooldown." + group);
        } else {

            return config.getLong("command-delay." + command + ".cooldown.default");
        }
    }

    private String getPlayerGroup(User user) {

        for (Node node : user.getNodes()) {

            if (node.getKey().startsWith("group.")) {

                return node.getKey().substring(6);
            }
        }

        return "default";
    }
}
