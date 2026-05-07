package so.max1soft.vexitychat.managers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class VexityAIManager {

    private final JavaPlugin plugin;
    private String apiUrl;
    private String licenseKey;
    private boolean debug;
    private final HttpClient httpClient;

    private volatile String sessionToken;
    private final java.util.concurrent.atomic.AtomicBoolean acquiring =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // Состояние связи с бэкендом
    private volatile boolean backendAvailable = true;
    private final java.util.concurrent.atomic.AtomicBoolean reconnectScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);



    private final java.io.File warningsFile;
    private org.bukkit.configuration.file.YamlConfiguration warningsConfig;

    private final Map<UUID, String> lastMessages = new HashMap<>();

    public VexityAIManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.warningsFile = new java.io.File(plugin.getDataFolder(), "warnings.yml");
        loadWarnings();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        reloadConfig();
    }

    private void loadWarnings() {
        if (!warningsFile.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                warningsFile.createNewFile();
            } catch (java.io.IOException e) {
                plugin.getLogger().severe("Could not create warnings.yml!");
            }
        }
        this.warningsConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(warningsFile);
    }

    private void saveWarnings() {
        try {
            warningsConfig.save(warningsFile);
        } catch (java.io.IOException e) {
            plugin.getLogger().severe("Could not save warnings.yml!");
        }
    }

    public void reloadConfig() {
        this.apiUrl = plugin.getConfig().getString("vexityai.api-url", "https://api.vexity.pw");
        this.licenseKey = plugin.getConfig().getString("vexityai.license-key", "");
        this.debug = plugin.getConfig().getBoolean("vexityai.debug", false);
        this.sessionToken = null;
        if (debug) plugin.getLogger().info("[Vexity-AI Debug] Config reloaded. API: " + apiUrl);
        if (licenseKey == null || licenseKey.isEmpty()) {
            plugin.getLogger().warning("[Vexity-AI] license-key не указан в config.yml — ИИ работать не будет");
        } else {
            acquireSession();
        }
    }

    public String getApiUrl() {
        return apiUrl;
    }

    private void acquireSession() {
        if (licenseKey == null || licenseKey.isEmpty()) return;
        if (!acquiring.compareAndSet(false, true)) return;

        JsonObject body = new JsonObject();
        body.addProperty("key", licenseKey);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/api/license/session"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    try {
                        if (resp.statusCode() == 200) {
                            JsonObject json = new JsonParser().parse(resp.body()).getAsJsonObject();
                            String token = json.has("token") ? json.get("token").getAsString() : null;
                            if (token != null && !token.isEmpty()) {
                                this.sessionToken = token;
                                this.backendAvailable = true;
                                if (debug) plugin.getLogger().info("[Vexity-AI Debug] Получен session-token (до " +
                                        (json.has("expires_at") ? json.get("expires_at").getAsString() : "?") + ")");
                            } else {
                                plugin.getLogger().warning("[Vexity-AI] /license/session: пустой ответ");
                            }
                        } else {
                            plugin.getLogger().warning("[Vexity-AI] /license/session HTTP " + resp.statusCode() + ": " + resp.body());
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("[Vexity-AI] Ошибка парсинга /license/session: " + e.getMessage());
                    } finally {
                        acquiring.set(false);
                    }
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("[Vexity-AI] /license/session недоступен: " + ex.getMessage());
                    acquiring.set(false);
                    return null;
                });
    }

    public void analyze(Player player, String message) {
        if (debug) plugin.getLogger().info("[Vexity-AI Debug] Анализ сообщения от " + player.getName() + ": " + message);
        
        if (!plugin.getConfig().getBoolean("vexityai.enabled", true)) {
            if (debug) plugin.getLogger().info("[Vexity-AI Debug] Vexity-AI выключен в конфиге.");
            return;
        }
        
        if (player.hasPermission("vexity.bypass")) {
            if (debug) plugin.getLogger().info("[Vexity-AI Debug] Игрок " + player.getName() + " имеет bypass. Пропускаем.");
            return;
        }

        if (checkRule25(player, message)) {
            if (debug) plugin.getLogger().info("[Vexity-AI Debug] Нарушено локальное правило 2.5 (Капс/Флуд).");
            return;
        }

        sendToAI(player, message);
    }

    private void sendToAI(Player player, String message) {
        sendToAI(player, message, false);
    }

    private void sendToAI(Player player, String message, boolean retried) {
        // Если бэкенд недоступен — пропускаем, reconnect уже запланирован
        if (!backendAvailable) return;

        String processedMessage = anonymizeMessage(message);
        if (debug) plugin.getLogger().info("[Vexity-AI Debug] Отправка запроса к ИИ: " + processedMessage);

        String token = this.sessionToken;
        if (token == null || token.isEmpty()) {
            if (debug) plugin.getLogger().info("[Vexity-AI Debug] Неверный или истёкший license-key");
            acquireSession();
            return;
        }

        JsonObject json = new JsonObject();
        json.addProperty("text", processedMessage);
        json.addProperty("player", player.getName());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/check"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() == 401 && !retried) {
                        if (debug) plugin.getLogger().info("[Vexity-AI Debug] /check вернул 401 — обновляем сессию");
                        this.sessionToken = null;
                        acquireSession();
                        Bukkit.getScheduler().runTaskLater(plugin,
                                () -> sendToAI(player, message, true), 10L);
                        return;
                    }
                    if (resp.statusCode() != 200) {
                        if (debug) plugin.getLogger().warning("[Vexity-AI Debug] /check HTTP " + resp.statusCode() + ": " + resp.body());
                        return;
                    }
                    // Связь восстановлена
                    if (!backendAvailable) {
                        backendAvailable = true;
                        plugin.getLogger().info("[Vexity-AI] Связь с бэкендом восстановлена.");
                    }
                    processAIResponse(player, resp.body());
                })
                .exceptionally(ex -> {
                    if (debug) plugin.getLogger().warning("[Vexity-AI Debug] Ошибка связи с ИИ: " + ex.getMessage());
                    if (backendAvailable) {
                        backendAvailable = false;
                        plugin.getLogger().warning("[Vexity-AI] Связь с бэкендом потеряна. Сообщения пропускаются. Переподключение через 10 сек...");
                    }
                    scheduleReconnect();
                    return null;
                });
    }

    private void scheduleReconnect() {
        if (!reconnectScheduled.compareAndSet(false, true)) return;
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            reconnectScheduled.set(false);
            if (backendAvailable) return; // уже восстановилось само
            if (debug) plugin.getLogger().info("[Vexity-AI Debug] Попытка переподключения...");
            // Пробуем получить новый session-token — если получим, значит бэкенд живой
            this.sessionToken = null;
            acquireSession();
            // Если acquireSession успешно — backendAvailable станет true при следующем /check
            // Если нет — scheduleReconnect вызовется снова из exceptionally
        }, 200L); // 200 ticks = 10 сек
    }

    private void processAIResponse(Player player, String responseBody) {
        if (debug) plugin.getLogger().info("[Vexity-AI Debug] Получен ответ от ИИ: " + responseBody);
        try {
            JsonObject response = new JsonParser().parse(responseBody).getAsJsonObject();
            if (response.has("violation") && response.get("violation").getAsBoolean()) {
                int category = response.get("category").getAsInt();
                String categoryStr = String.valueOf(category);
                
                if (debug) plugin.getLogger().info("[Vexity-AI Debug] ИИ нашел нарушение! Категория: " + categoryStr);
                
    
                String path = "vexityai.progressive.rules." + categoryStr;
                if (plugin.getConfig().contains(path)) {
                    Bukkit.getScheduler().runTask(plugin, () -> executePunishment(player, categoryStr));
                } else {
                    if (debug) plugin.getLogger().warning("[Vexity-AI Debug] В конфиге нет настроек для категории " + categoryStr);
                }
            } else {
                if (debug) plugin.getLogger().info("[Vexity-AI Debug] Нарушений не обнаружено.");
            }
        } catch (Exception e) {
            if (debug) plugin.getLogger().warning("[Vexity-AI Debug] Ошибка парсинга: " + e.getMessage());
        }
    }

    private void executePunishment(Player player, String categoryId) {
        String path = "vexityai.progressive.rules." + categoryId;
        String ruleDisplayId = plugin.getConfig().getString(path + ".rule-id", categoryId);
        
        if (debug) plugin.getLogger().info("[Vexity-AI Debug] Выполнение наказания для " + player.getName() + " по правилу " + ruleDisplayId);
        
        ProgressiveResult result = handleProgressive(player, categoryId, ruleDisplayId);
        
        if (result.command != null && !result.command.isEmpty()) {
            if (result.command.startsWith("{NOTIFY}")) {
                String message = result.command.replace("{NOTIFY}", "").trim();
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', message.replace("{player}", player.getName())));
                if (debug) plugin.getLogger().info("[Vexity-AI Debug] Отправлено прямое уведомление игроку.");
            } else {
                String cmd = result.command.replace("{player}", player.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                if (debug) plugin.getLogger().info("[Vexity-AI Debug] Выполнена команда консоли: " + cmd);
                
            
                if (result.isFinalWarning) {
                    String notifyMsg = plugin.getConfig().getString("vexityai.notify-message");
                    if (notifyMsg != null) {
                        String formattedMsg = ChatColor.translateAlternateColorCodes('&', 
                            notifyMsg.replace("{player}", player.getName()).replace("{rule}", ruleDisplayId));
                        
                        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                            if (onlinePlayer.hasPermission("vexity.ai.notify")) {
                                onlinePlayer.sendMessage(formattedMsg);
                            }
                        }
                        Bukkit.getConsoleSender().sendMessage(formattedMsg);
                        if (debug) plugin.getLogger().info("[Vexity-AI Debug] Отправлено уведомление модераторам (финальный варн).");
                    }
                }
            }
        } else {
            if (debug) plugin.getLogger().warning("[Vexity-AI Debug] Команда для наказания пуста или не найдена!");
        }
    }

    private ProgressiveResult handleProgressive(Player player, String categoryId, String ruleId) {
        String uuid = player.getUniqueId().toString();
        String path = "players." + uuid + "." + categoryId;
        
        int count = warningsConfig.getInt(path + ".count", 0);
        long lastViolation = warningsConfig.getLong(path + ".last", 0);
        long now = System.currentTimeMillis();
        
        long expireMillis = parseTime(plugin.getConfig().getString("vexityai.progressive.expire-time", "24h"));
        if (now - lastViolation > expireMillis) {
            if (debug) plugin.getLogger().info("[Vexity-AI Debug] Варны игрока истекли, сбрасываем.");
            count = 0;
        }

        int maxWarnings = plugin.getConfig().getInt("vexityai.progressive.rules." + categoryId + ".max-warnings", 1);
        count++;
        
        boolean isFinalWarning = (count >= maxWarnings);
        
        if (isFinalWarning) {
            if (debug) plugin.getLogger().info("[Vexity-AI Debug] Достигнут максимум варнов (" + maxWarnings + "). Сбрасываем историю для " + player.getName());
            warningsConfig.set(path + ".count", 0);
        } else {
            warningsConfig.set(path + ".count", count);
        }
        
        warningsConfig.set(path + ".last", now);
        saveWarnings();

        if (debug) plugin.getLogger().info("[Vexity-AI Debug] Игрок " + player.getName() + " получил варн №" + count + " за категорию " + categoryId);

        String levelPath = "vexityai.progressive.rules." + categoryId + ".levels." + count;
        String cmd = plugin.getConfig().getString(levelPath);
        
        if (cmd == null) {
            cmd = plugin.getConfig().getString("vexityai.progressive.rules." + categoryId + ".levels." + maxWarnings);
            if (debug) plugin.getLogger().info("[Vexity-AI Debug] Уровень " + count + " не найден, используем максимальный (уровень " + maxWarnings + ")");
        }
        
        if (debug) plugin.getLogger().info("[Vexity-AI Debug] Команда для выполнения: " + cmd);
        
        return new ProgressiveResult(cmd, isFinalWarning);
    }
    

    private static class ProgressiveResult {
        final String command;
        final boolean isFinalWarning;
        
        ProgressiveResult(String command, boolean isFinalWarning) {
            this.command = command;
            this.isFinalWarning = isFinalWarning;
        }
    }

    private boolean checkRule25(Player player, String message) {
        if (!plugin.getConfig().getBoolean("vexityai.anti-caps.enabled", true) && 
            !plugin.getConfig().getBoolean("vexityai.anti-flood.enabled", true)) return false;

        boolean violated = false;
        
   
        if (plugin.getConfig().getBoolean("vexityai.anti-caps.enabled", true)) {
            int minLen = plugin.getConfig().getInt("vexityai.anti-caps.min-length", 5);
            if (message.length() >= minLen) {
                long upper = message.chars().filter(Character::isUpperCase).count();
                double percent = (double) upper / message.length() * 100;
                if (debug && percent > 30) plugin.getLogger().info("[Vexity-AI Debug] Проверка капса: " + String.format("%.1f", percent) + "%");
                if (percent >= plugin.getConfig().getInt("vexityai.anti-caps.percent", 70)) violated = true;
            }
        }


        if (!violated && plugin.getConfig().getBoolean("vexityai.anti-flood.enabled", true)) {
            int maxRepeat = plugin.getConfig().getInt("vexityai.anti-flood.max-repeating-chars", 4);
            int count = 1;
            for (int i = 1; i < message.length(); i++) {
                if (message.charAt(i) == message.charAt(i - 1)) {
                    count++;
                    if (count > maxRepeat) {
                        violated = true;
                        if (debug) plugin.getLogger().info("[Vexity-AI Debug] Флуд символами: '" + message.charAt(i) + "' повторился " + count + " раз.");
                        break;
                    }
                } else count = 1;
            }
        }


        if (!violated && plugin.getConfig().getBoolean("vexityai.anti-flood.enabled", true)) {
            String lastMsg = lastMessages.get(player.getUniqueId());
            if (lastMsg != null) {
                double similarity = calculateSimilarity(message, lastMsg);
                if (debug && similarity > 0.5) plugin.getLogger().info("[Vexity-AI Debug] Сходство сообщений: " + String.format("%.1f", similarity * 100) + "%");
                double minSimilarity = plugin.getConfig().getDouble("vexityai.anti-flood.min-similarity", 0.8);
                if (similarity >= minSimilarity) violated = true;
            }
            lastMessages.put(player.getUniqueId(), message);
        }

        if (violated) {
            Bukkit.getScheduler().runTask(plugin, () -> executePunishment(player, "4"));
            return true;
        }
        return false;
    }

    private double calculateSimilarity(String s1, String s2) {
        String longer = s1, shorter = s2;
        if (s1.length() < s2.length()) {
            longer = s2; shorter = s1;
        }
        int longerLength = longer.length();
        if (longerLength == 0) return 1.0;
        return (longerLength - editDistance(longer, shorter)) / (double) longerLength;
    }

    private int editDistance(String s1, String s2) {
        s1 = s1.toLowerCase(); s2 = s2.toLowerCase();
        int[] costs = new int[s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) {
            int lastValue = i;
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) costs[j] = j;
                else {
                    if (j > 0) {
                        int newValue = costs[j - 1];
                        if (s1.charAt(i - 1) != s2.charAt(j - 1))
                            newValue = Math.min(Math.min(newValue, lastValue), costs[j]) + 1;
                        costs[j - 1] = lastValue;
                        lastValue = newValue;
                    }
                }
            }
            if (i > 0) costs[s2.length()] = lastValue;
        }
        return costs[s2.length()];
    }

    private long parseTime(String time) {
        try {
            if (time.endsWith("h")) return Long.parseLong(time.replace("h", "")) * 3600000L;
            if (time.endsWith("m")) return Long.parseLong(time.replace("m", "")) * 60000L;
            if (time.endsWith("s")) return Long.parseLong(time.replace("s", "")) * 1000L;
            if (time.endsWith("d")) return Long.parseLong(time.replace("d", "")) * 86400000L;
            return Long.parseLong(time);
        } catch (Exception e) {
            return 86400000L;
        }
    }

    private String anonymizeMessage(String message) {
        String result = message;
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            result = result.replaceAll("(?i)\\b" + onlinePlayer.getName() + "\\b", "игрок");
        }
        return result;
    }
}
