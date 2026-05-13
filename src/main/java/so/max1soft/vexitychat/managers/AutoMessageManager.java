package so.max1soft.vexitychat.managers;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import so.max1soft.vexitychat.utils.ColorUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class AutoMessageManager {

    private final JavaPlugin plugin;
    private final List<List<String>> messages = new ArrayList<>();
    private boolean enabled;
    private boolean random;
    private int interval;
    private int currentIndex = 0;
    private final Random rand = new Random();
    private BukkitTask task;

    public AutoMessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
        if (enabled && !messages.isEmpty()) {
            startTask();
        }
    }

    private void loadConfig() {
        messages.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("autoMessage");
        if (section == null) return;

        this.enabled = section.getBoolean("enable", false);
        this.random = section.getBoolean("random", false);
        this.interval = section.getInt("messageInterval", 300);

        ConfigurationSection msgSection = section.getConfigurationSection("messages");
        if (msgSection != null) {
            for (String key : msgSection.getKeys(false)) {
                List<String> lines = msgSection.getStringList(key);
                if (!lines.isEmpty()) {
                    messages.add(lines);
                }
            }
        }
    }

    private void startTask() {
        task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!enabled || messages.isEmpty()) return;

                List<String> lines;
                if (random) {
                    lines = messages.get(rand.nextInt(messages.size()));
                } else {
                    lines = messages.get(currentIndex);
                    currentIndex = (currentIndex + 1) % messages.size();
                }

                for (String line : lines) {
                    Bukkit.broadcastMessage(ColorUtil.translateColorCodes(line));
                }
            }
        }.runTaskTimer(plugin, interval * 20L, interval * 20L);
    }

    public void reload() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        currentIndex = 0;
        loadConfig();
        if (enabled && !messages.isEmpty()) {
            startTask();
        }
    }
}
