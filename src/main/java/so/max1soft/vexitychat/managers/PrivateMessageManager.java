package so.max1soft.vexitychat.managers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PrivateMessageManager {

    private final Map<UUID, UUID> lastMessaged = new ConcurrentHashMap<>();
    private final FileConfiguration config;

    public PrivateMessageManager(FileConfiguration config) {
        this.config = config;
    }

    public void sendPrivateMessage(Player sender, Player receiver, String message) {
        String senderMessage = config.getString("message-you").replace("%player%", receiver.getName()).replace("%message%", message);
        String receiverMessage = config.getString("message-nyou").replace("%player%", sender.getName()).replace("%message%", message);

        sender.sendMessage(senderMessage);
        receiver.sendMessage(receiverMessage);


        lastMessaged.put(sender.getUniqueId(), receiver.getUniqueId());
        lastMessaged.put(receiver.getUniqueId(), sender.getUniqueId());
    }

    public Player getLastMessaged(Player player) {
        UUID receiverId = lastMessaged.get(player.getUniqueId());
        return receiverId == null ? null : Bukkit.getPlayer(receiverId);
    }
}
