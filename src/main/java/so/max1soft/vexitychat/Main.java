package so.max1soft.vexitychat;

import net.luckperms.api.LuckPerms;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import so.max1soft.vexitychat.commands.*;
import so.max1soft.vexitychat.filters.ChatDelay;
import so.max1soft.vexitychat.filters.ChatFilter;
import so.max1soft.vexitychat.filters.CommandFilterListener;
import so.max1soft.vexitychat.listeners.ChatConstructor;
import so.max1soft.vexitychat.listeners.ChatListener;
import so.max1soft.vexitychat.managers.AutoMessageManager;
import so.max1soft.vexitychat.managers.PlaytimeDatabase;
import so.max1soft.vexitychat.managers.VexityAIManager;

import java.util.List;

public class Main extends JavaPlugin implements Listener {
    private LuckPerms luckPerms;
    private ChatFilter chatFilter;
    private ChatDelay chatDelay;
    private ChatConstructor chatConstructor;
    private List<String> hoverText;
    private VexityAIManager aiManager;
    private PlaytimeDatabase playtimeDatabase;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        playtimeDatabase = new PlaytimeDatabase(this);

        RegisteredServiceProvider<LuckPerms> provider = getServer().getServicesManager()
                .getRegistration(LuckPerms.class);
        if (provider != null) {
            luckPerms = provider.getProvider();
        }
        hoverText = getConfig().getStringList("hover.lines");

        long switchToAdvancedTime = getConfig().getLong("time.switch-to-advanced");
        long chatDelayTime = getConfig().getLong("time.chat-delay");
        chatDelay = new ChatDelay(this, switchToAdvancedTime, chatDelayTime, playtimeDatabase);
        getServer().getPluginManager().registerEvents(chatDelay, this);
        chatFilter = new ChatFilter(this, getConfig(), chatDelay);

        chatConstructor = new ChatConstructor(getConfig(), luckPerms);
        getServer().getPluginManager().registerEvents(new CommandDelay(getConfig(), luckPerms), this);
        getServer().getPluginManager().registerEvents(this, this);
        aiManager = new VexityAIManager(this);
        ChatListener chatListener = new ChatListener(chatConstructor, chatFilter, hoverText, luckPerms, this, aiManager);

        // Регистрируем Paper listener (1.19+) если доступен, иначе legacy
        boolean paperChat = false;
        try {
            Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
            getServer().getPluginManager().registerEvents(
                new so.max1soft.vexitychat.listeners.PaperChatListener(chatListener), this);
            paperChat = true;
            getLogger().info("[VexityChat] Используется Paper chat API (1.19+)");
        } catch (ClassNotFoundException ignored) {}

        if (!paperChat) {
            getServer().getPluginManager().registerEvents(chatListener, this);
            getLogger().info("[VexityChat] Используется Legacy chat API (1.16-1.18)");
        }
        CommandFilterListener commandFilterListener = new CommandFilterListener(chatFilter, chatDelay, this);
        Bukkit.getPluginManager().registerEvents(commandFilterListener, this);
        new AutoMessageManager(this);

        MainCommand mainCommand = new MainCommand(this, aiManager);
        getCommand("vexity").setExecutor(mainCommand);
    }

    @Override
    public void onDisable() {
        if (chatDelay != null) chatDelay.saveAll();
        if (playtimeDatabase != null) playtimeDatabase.close();
    }

}