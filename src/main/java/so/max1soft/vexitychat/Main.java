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
import so.max1soft.vexitychat.menus.ReportsMenuManager;
import so.max1soft.vexitychat.managers.AutoMessageManager;
import so.max1soft.vexitychat.managers.PlaytimeDatabase;
import so.max1soft.vexitychat.managers.ReportsManager;
import so.max1soft.vexitychat.managers.VexityAIManager;
import so.max1soft.vexitychat.managers.WarningsDatabase;

public class Main extends JavaPlugin implements Listener {
    private LuckPerms luckPerms;
    private ChatFilter chatFilter;
    private ChatDelay chatDelay;
    private ChatConstructor chatConstructor;
    private VexityAIManager aiManager;
    private PlaytimeDatabase playtimeDatabase;
    private WarningsDatabase warningsDatabase;
    private AutoMessageManager autoMessageManager;
    private ReportsManager reportsManager;
    private ReportsMenuManager reportsMenuManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        playtimeDatabase = new PlaytimeDatabase(this);
        warningsDatabase = new WarningsDatabase(this);
        reportsManager = new ReportsManager(this);

        RegisteredServiceProvider<LuckPerms> provider = getServer().getServicesManager()
                .getRegistration(LuckPerms.class);
        if (provider != null) {
            luckPerms = provider.getProvider();
        }

        chatDelay = new ChatDelay(this, playtimeDatabase);
        getServer().getPluginManager().registerEvents(chatDelay, this);
        chatFilter = new ChatFilter(this, chatDelay);

        chatConstructor = new ChatConstructor(this, luckPerms);
        getServer().getPluginManager().registerEvents(new CommandDelay(this, luckPerms), this);
        getServer().getPluginManager().registerEvents(this, this);
        aiManager = new VexityAIManager(this, reportsManager, warningsDatabase);
        ChatListener chatListener = new ChatListener(chatConstructor, chatFilter, luckPerms, this, aiManager);

        // Регистрируем Paper listener (1.19+) если доступен, иначе legacy
        boolean paperChat = false;
        try {
            Class<?> asyncChatEventClass = Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
            Class<?> paperListenerClass = Class.forName("so.max1soft.vexitychat.listeners.PaperChatListener");
            Object paperListener = paperListenerClass.getConstructor(ChatListener.class).newInstance(chatListener);
            getServer().getPluginManager().registerEvents((Listener) paperListener, this);
            paperChat = true;
            getLogger().info("[VexityChat] Используется Paper chat API (1.19+)");
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException | java.lang.reflect.InvocationTargetException ignored) {}

        if (!paperChat) {
            getServer().getPluginManager().registerEvents(chatListener, this);
            getLogger().info("[VexityChat] Используется Legacy chat API (1.16-1.18)");
        }
        CommandFilterListener commandFilterListener = new CommandFilterListener(chatFilter, chatDelay, this);
        Bukkit.getPluginManager().registerEvents(commandFilterListener, this);
        autoMessageManager = new AutoMessageManager(this);
        reportsMenuManager = new ReportsMenuManager(this, reportsManager);
        Bukkit.getPluginManager().registerEvents(reportsMenuManager, this);

        MainCommand mainCommand = new MainCommand(this, aiManager, reportsMenuManager);
        getCommand("vexity").setExecutor(mainCommand);
        getCommand("vexity").setTabCompleter(mainCommand);
    }

    public void reloadRuntimeConfig() {
        reloadConfig();
        if (aiManager != null) aiManager.reloadConfig();
        if (autoMessageManager != null) autoMessageManager.reload();
        if (reportsMenuManager != null) reportsMenuManager.reload();
    }

    @Override
    public void onDisable() {
        if (chatDelay != null) chatDelay.saveAll();
        if (reportsManager != null) reportsManager.close();
        if (warningsDatabase != null) warningsDatabase.close();
        if (playtimeDatabase != null) playtimeDatabase.close();
    }

}
