package so.max1soft.vexitychat.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import so.max1soft.vexitychat.Main;
import so.max1soft.vexitychat.menus.ReportsMenuManager;
import so.max1soft.vexitychat.managers.VexityAIManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainCommand implements TabExecutor {

    private static final long REINSTALL_CONFIRM_TIMEOUT_MS = 30_000L;

    private final JavaPlugin plugin;
    private final VexityAIManager aiManager;
    private final ReportsMenuManager reportsMenuManager;
    private final HttpClient httpClient;
    private final Map<String, Long> reinstallConfirmations = new HashMap<>();

    public MainCommand(JavaPlugin plugin, VexityAIManager aiManager, ReportsMenuManager reportsMenuManager) {
        this.plugin = plugin;
        this.aiManager = aiManager;
        this.reportsMenuManager = reportsMenuManager;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!sender.hasPermission("vexity.admin")) {
                sender.sendMessage(ChatColor.RED + "У вас нет прав!");
                return true;
            }
            sendHelp(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("reports")) {
            openReports(sender);
            return true;
        }

        if (!sender.hasPermission("vexity.admin")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав!");
            return true;
        }

        if (args[0].equalsIgnoreCase("ai")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.YELLOW + "Использование: /" + label + " ai <status|reload>");
                return true;
            }

            if (args[1].equalsIgnoreCase("status")) {
                checkAIStatus(sender);
                return true;
            }

            if (args[1].equalsIgnoreCase("reload")) {
                plugin.reloadConfig();
                aiManager.reloadConfig();
                sender.sendMessage(ChatColor.GREEN + "[Vexity-AI] Конфигурация ИИ успешно перезагружена!");
                return true;
            }
        }

        if (args[0].equalsIgnoreCase("reinstall")) {
            handleReinstall(sender, label);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadPluginConfig();
            sender.sendMessage(ChatColor.GREEN + "Плагин успешно перезагружен!");
            return true;
        }

        sendHelp(sender);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission("vexity.admin");
        boolean staff = sender.hasPermission("vexity.staff");
        if (!admin && !staff) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (admin) {
                options.addAll(Arrays.asList("reload", "reinstall", "ai"));
            }
            if (staff) {
                options.add("reports");
            }
            return filterSuggestions(args[0], options);
        }

        if (admin && args.length == 2 && args[0].equalsIgnoreCase("ai")) {
            return filterSuggestions(args[1], Arrays.asList("status", "reload"));
        }

        return Collections.emptyList();
    }

    private List<String> filterSuggestions(String input, List<String> options) {
        String prefix = input.toLowerCase();
        List<String> matches = new ArrayList<>();

        for (String option : options) {
            if (option.startsWith(prefix)) {
                matches.add(option);
            }
        }

        return matches;
    }

    private void openReports(CommandSender sender) {
        if (!sender.hasPermission("vexity.staff")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав!");
            return;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Эта команда доступна только игроку.");
            return;
        }

        reportsMenuManager.openMainMenu((Player) sender);
    }

    private void reloadPluginConfig() {
        if (plugin instanceof Main) {
            ((Main) plugin).reloadRuntimeConfig();
            return;
        }

        plugin.reloadConfig();
        aiManager.reloadConfig();
    }

    private void handleReinstall(CommandSender sender, String label) {
        String key = sender.getName().toLowerCase();
        long now = System.currentTimeMillis();
        long confirmedAt = reinstallConfirmations.getOrDefault(key, 0L);

        if (now - confirmedAt > REINSTALL_CONFIRM_TIMEOUT_MS) {
            reinstallConfirmations.put(key, now);
            sender.sendMessage(ChatColor.YELLOW + "[Vexity] Эта команда добавит недостающие поля в config.yml и menu.yml, не меняя текущие значения.");
            sender.sendMessage(ChatColor.YELLOW + "[Vexity] Для подтверждения повторите: " + ChatColor.GOLD + "/" + label + " reinstall");
            return;
        }

        reinstallConfirmations.remove(key);
        try {
            int added = reinstallConfig();
            reloadPluginConfig();
            sender.sendMessage(ChatColor.GREEN + "[Vexity] Reinstall завершён. Добавлено недостающих полей: " + added);
            sender.sendMessage(ChatColor.GRAY + "[Vexity] Текущие настроенные значения сохранены.");
        } catch (IOException e) {
            sender.sendMessage(ChatColor.RED + "[Vexity] Не удалось обновить config.yml: " + e.getMessage());
            plugin.getLogger().severe("Could not reinstall config.yml: " + e.getMessage());
        }
    }

    private int reinstallConfig() throws IOException {
        return reinstallYamlResource("config.yml") + reinstallYamlResource("menu.yml");
    }

    private int reinstallYamlResource(String resourceName) throws IOException {
        File file = new File(plugin.getDataFolder(), resourceName);
        if (!file.exists()) {
            plugin.saveResource(resourceName, false);
        }

        FileConfiguration currentConfig = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaultConfig;

        try (InputStream stream = plugin.getResource(resourceName)) {
            if (stream == null) {
                throw new IOException("default " + resourceName + " not found in plugin jar");
            }
            defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }

        int added = mergeMissingDefaults(currentConfig, defaultConfig);
        if (added > 0) {
            currentConfig.save(file);
        }
        return added;
    }

    private int mergeMissingDefaults(ConfigurationSection target, ConfigurationSection defaults) {
        int added = 0;

        for (String key : defaults.getKeys(false)) {
            Object defaultValue = defaults.get(key);

            if (defaultValue instanceof ConfigurationSection) {
                ConfigurationSection targetSection = target.getConfigurationSection(key);
                if (target.contains(key) && targetSection == null) {
                    continue;
                }
                if (targetSection == null) {
                    targetSection = target.createSection(key);
                    added++;
                }
                added += mergeMissingDefaults(targetSection, (ConfigurationSection) defaultValue);
                continue;
            }

            if (!target.contains(key)) {
                target.set(key, defaultValue);
                added++;
            }
        }

        return added;
    }

    private void checkAIStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "[Vexity-AI] Проверка связи с бэкендом...");
        
        // Проверяем доступность API через /health
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(aiManager.getApiUrl() + "/health"))
                .GET()
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> {
                    if (res.statusCode() == 200) {
                        sender.sendMessage(ChatColor.GREEN + "[Vexity-AI] Статус: ОНЛАЙН (Версия: Deep CNN)");
                        sender.sendMessage(ChatColor.GRAY + "URL: " + aiManager.getApiUrl());
                    } else {
                        sender.sendMessage(ChatColor.RED + "[Vexity-AI] Статус: ОШИБКА (Код: " + res.statusCode() + ")");
                    }
                })
                .exceptionally(ex -> {
                    sender.sendMessage(ChatColor.RED + "[Vexity-AI] Статус: ОФФЛАЙН (Бэкенд не отвечает)");
                    return null;
                });
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "--- VexityChat Admin ---");
        sender.sendMessage(ChatColor.YELLOW + "/vexity reload " + ChatColor.GRAY + "- Перезагрузить плагин");
        sender.sendMessage(ChatColor.YELLOW + "/vexity reinstall " + ChatColor.GRAY + "- Добавить недостающие поля в config.yml/menu.yml");
        sender.sendMessage(ChatColor.YELLOW + "/vexity reports " + ChatColor.GRAY + "- Открыть меню нарушений");
        sender.sendMessage(ChatColor.YELLOW + "/vexity ai status " + ChatColor.GRAY + "- Проверить ИИ");
        sender.sendMessage(ChatColor.YELLOW + "/vexity ai reload " + ChatColor.GRAY + "- Перезагрузить настройки ИИ");
    }
}
