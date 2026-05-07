package so.max1soft.vexitychat.filters;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import so.max1soft.vexitychat.managers.PlaytimeDatabase;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ChatDelay implements Listener {

    private final JavaPlugin plugin;
    private final long switchToAdvancedTime;
    private final long chatDelayTime;
    private final PlaytimeDatabase db;
    private final boolean papiAvailable;

    // Кэш плейтайма — обновляется раз в 30 сек чтобы не дёргать PAPI/БД на каждое сообщение
    private final Map<UUID, Long> playtimeCache = new HashMap<>();
    private final Map<UUID, Long> playtimeCacheTime = new HashMap<>();
    private static final long CACHE_TTL_MS = 30_000;

    // Время входа в текущую сессию (для подсчёта прироста)
    private final Map<UUID, Long> sessionStart = new HashMap<>();

    public ChatDelay(JavaPlugin plugin, long switchToAdvancedTime, long chatDelayTime, PlaytimeDatabase db) {
        this.plugin = plugin;
        this.switchToAdvancedTime = switchToAdvancedTime;
        this.chatDelayTime = chatDelayTime;
        this.db = db;
        this.papiAvailable = org.bukkit.Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sessionStart.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        // Сбрасываем кэш при входе
        playtimeCache.remove(event.getPlayer().getUniqueId());
        playtimeCacheTime.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        saveSession(uuid);
        sessionStart.remove(uuid);
        playtimeCache.remove(uuid);
        playtimeCacheTime.remove(uuid);
    }

    /** Сохраняет накопленное за сессию время в БД. */
    public void saveSession(UUID uuid) {
        Long start = sessionStart.get(uuid);
        if (start == null) return;
        long elapsed = (System.currentTimeMillis() - start) / 1000;
        if (elapsed > 0) db.addSeconds(uuid, elapsed);
        // Сбрасываем старт чтобы не считать дважды
        sessionStart.put(uuid, System.currentTimeMillis());
    }

    /** Сохраняет все активные сессии (при выключении сервера). */
    public void saveAll() {
        for (UUID uuid : sessionStart.keySet()) {
            saveSession(uuid);
        }
    }

    public boolean canChat(Player player) {
        if (player == null) return false;
        return getPlayerPlaytime(player) >= chatDelayTime;
    }

    public long getPlayerPlaytime(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // Проверяем кэш
        Long cached = playtimeCache.get(uuid);
        Long cacheTime = playtimeCacheTime.get(uuid);
        if (cached != null && cacheTime != null && now - cacheTime < CACHE_TTL_MS) {
            // Добавляем время текущей сессии поверх кэша
            Long start = sessionStart.get(uuid);
            long sessionDelta = start != null ? (now - start) / 1000 : 0;
            return cached + sessionDelta;
        }

        long playtime = fetchPlaytime(player);
        playtimeCache.put(uuid, playtime);
        playtimeCacheTime.put(uuid, now);
        return playtime;
    }

    private long fetchPlaytime(Player player) {
        // 1. PlaceholderAPI
        if (papiAvailable) {
            try {
                String val = me.clip.placeholderapi.PlaceholderAPI
                        .setPlaceholders(player, "%statistic_seconds_played%");
                long parsed = parseLongOrZero(val);
                if (parsed > 0) return parsed;
            } catch (Exception ignored) {}
        }

        // 2. БД + сессия
        long stored = db.isAvailable() ? db.getSeconds(player.getUniqueId()) : 0;
        Long start = sessionStart.get(player.getUniqueId());
        long sessionSeconds = start != null ? (System.currentTimeMillis() - start) / 1000 : 0;
        return stored + sessionSeconds;
    }

    /** Версия для ChatFilter — принимает уже вычисленный плейтайм */
    public String getAllowedSymbolsForPlayer(long playtime) {
        if (playtime >= switchToAdvancedTime) {
            return plugin.getConfig().getString("allowed-symbols.advanced");
        }
        return plugin.getConfig().getString("allowed-symbols.standard");
    }

    public String getAllowedSymbolsForPlayer(Player player) {
        if (player == null) return "";
        return getAllowedSymbolsForPlayer(getPlayerPlaytime(player));
    }

    public long getADelayTime() { return switchToAdvancedTime; }
    public long getChatDelayTime() { return chatDelayTime; }

    private long parseLongOrZero(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
