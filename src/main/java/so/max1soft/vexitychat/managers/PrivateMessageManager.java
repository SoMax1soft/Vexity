package so.max1soft.vexitychat.managers;

import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;

public class PrivateMessageManager {

    private final Map<Player, Player> lastMessaged = new HashMap<>();
    private final FileConfiguration config;

    public PrivateMessageManager(FileConfiguration config) {
        this.config = config;
    }

    public void sendPrivateMessage(Player sender, Player receiver, String message) {
        String senderMessage = config.getString("message-you").replace("%player%", receiver.getName()).replace("%message%", message);
        String receiverMessage = config.getString("message-nyou").replace("%player%", sender.getName()).replace("%message%", message);

        sender.sendMessage(senderMessage);
        receiver.sendMessage(receiverMessage);


        lastMessaged.put(sender, receiver);
        lastMessaged.put(receiver, sender);
    }

    public Player getLastMessaged(Player player) {
        return lastMessaged.get(player);
    }
}
