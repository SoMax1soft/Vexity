package so.max1soft.vexitychat.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import so.max1soft.vexitychat.managers.VexityAIManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class MainCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final VexityAIManager aiManager;
    private final HttpClient httpClient;

    public MainCommand(JavaPlugin plugin, VexityAIManager aiManager) {
        this.plugin = plugin;
        this.aiManager = aiManager;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vexity.admin")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав!");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
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

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            aiManager.reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "Плагин успешно перезагружен!");
            return true;
        }

        sendHelp(sender);
        return true;
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
        sender.sendMessage(ChatColor.YELLOW + "/vexity ai status " + ChatColor.GRAY + "- Проверить ИИ");
        sender.sendMessage(ChatColor.YELLOW + "/vexity ai reload " + ChatColor.GRAY + "- Перезагрузить настройки ИИ");
    }
}
