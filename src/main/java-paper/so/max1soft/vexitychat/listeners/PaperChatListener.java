package so.max1soft.vexitychat.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Обработчик чата для Paper modern chat API.
 * Класс загружается только если Paper API доступен (проверяется в Main через Class.forName).
 */
@SuppressWarnings("UnstableApiUsage")
public class PaperChatListener implements Listener {

    private static final Pattern CONTENT_PATTERN = Pattern.compile("content=\\\"([^\\\"]*)\\\"");

    private final ChatListener delegate;

    public PaperChatListener(ChatListener delegate) {
        this.delegate = delegate;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        String message = plainText(event.message());
        delegate.handleChat(
                event.getPlayer(),
                message,
                () -> event.setCancelled(true),
                event::isCancelled);
    }

    private String plainText(Object component) {
        if (component == null) return "";

        StringBuilder text = new StringBuilder();
        appendContent(component, text);
        if (text.length() > 0) return text.toString();

        Matcher matcher = CONTENT_PATTERN.matcher(String.valueOf(component));
        return matcher.find() ? matcher.group(1) : String.valueOf(component);
    }

    private void appendContent(Object component, StringBuilder text) {
        String content = readStringMethod(component, "content");
        if (content != null) text.append(content);

        Object children = invokeNoArg(component, "children");
        if (children instanceof Iterable<?>) {
            for (Object child : (Iterable<?>) children) {
                appendContent(child, text);
            }
        }
    }

    private String readStringMethod(Object target, String methodName) {
        Object value = invokeNoArg(target, methodName);
        return value instanceof String ? (String) value : null;
    }

    private Object invokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
