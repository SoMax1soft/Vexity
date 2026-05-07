package so.max1soft.vexitychat.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Обработчик чата для Paper 1.19+.
 * Класс загружается только если Paper API доступен (проверяется в Main через Class.forName).
 */
@SuppressWarnings("UnstableApiUsage")
public class PaperChatListener implements Listener {

    private final ChatListener delegate;

    public PaperChatListener(ChatListener delegate) {
        this.delegate = delegate;
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        delegate.handleChat(event.getPlayer(), message, () -> event.setCancelled(true));
    }
}
