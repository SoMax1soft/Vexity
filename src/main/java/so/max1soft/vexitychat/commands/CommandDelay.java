package so.max1soft.vexitychat.commands;

import net.luckperms.api.node.Node;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class CommandDelay implements Listener {

    private final FileConfiguration config;
    private final LuckPerms luckPerms;
    private final Map<String, Long> commandTimestamps = new HashMap<>();

    public CommandDelay(FileConfiguration config, LuckPerms luckPerms) {
        this.config = config;
        this.luckPerms = luckPerms;
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage().toLowerCase().substring(1);


        org.bukkit.configuration.ConfigurationSection delaySection = config.getConfigurationSection("command-delay");
        if (delaySection == null) return;

        for (String configCommand : delaySection.getKeys(false)) {            List<String> aliases = config.getStringList("command-delay." + configCommand + ".aliases");
            if (isCommandOrAlias(message, configCommand, aliases)) {

                long delay = getCooldownForPlayer(player, configCommand);
                long lastExecuted = commandTimestamps.getOrDefault(configCommand + player.getName(), 0L);
                long currentTime = System.currentTimeMillis();


                if (currentTime - lastExecuted < delay * 1000) {
                    long remainingTime = (delay * 1000 - (currentTime - lastExecuted)) / 1000;
                    String messageTemplate = config.getString("command-delay-message");
                    if (messageTemplate != null) {
                        String finalMessage = messageTemplate.replace("{TIME}", String.valueOf(remainingTime));
                        player.sendMessage(finalMessage);
                    } else {
                        player.sendMessage("ПЭЛЬМЕНИ С МАЙОНЕЗАМ.");
                    }
                    event.setCancelled(true);
                    return;
                } else {

                    commandTimestamps.put(configCommand + player.getName(), currentTime);
                    break;
                }
            }
        }
    }

    private boolean isCommandOrAlias(String message, String command, List<String> aliases) {

        if (message.startsWith(command.toLowerCase())) {
            return true;
        }
        for (String alias : aliases) {
            if (message.startsWith(alias.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private long getCooldownForPlayer(Player player, String command) {

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
