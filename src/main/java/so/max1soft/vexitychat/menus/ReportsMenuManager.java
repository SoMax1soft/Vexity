package so.max1soft.vexitychat.menus;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import so.max1soft.vexitychat.managers.PlayerReportSummary;
import so.max1soft.vexitychat.managers.ReportEntry;
import so.max1soft.vexitychat.managers.ReportSort;
import so.max1soft.vexitychat.managers.ReportsManager;
import so.max1soft.vexitychat.utils.ColorUtil;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class ReportsMenuManager implements Listener {

    private final JavaPlugin plugin;
    private final ReportsManager reportsManager;
    private final File menuFile;
    private YamlConfiguration menuConfig;

    public ReportsMenuManager(JavaPlugin plugin, ReportsManager reportsManager) {
        this.plugin = plugin;
        this.reportsManager = reportsManager;
        this.menuFile = new File(plugin.getDataFolder(), "menu.yml");
        saveDefaultMenu();
        reload();
    }

    public void reload() {
        this.menuConfig = YamlConfiguration.loadConfiguration(menuFile);
    }

    public void openMainMenu(Player viewer) {
        openMainMenu(viewer, 0, null, ReportSort.LATEST);
    }

    private void openMainMenu(Player viewer, int page, String categoryFilter, ReportSort sort) {
        openLoadingMenu(viewer);
        reportsManager.loadSummariesAsync(categoryFilter, sort, summaries -> {
            if (!viewer.isOnline()) return;
            renderMainMenu(viewer, summaries, page, categoryFilter, sort);
        });
    }

    private void openPlayerMenu(Player viewer, UUID playerId, String playerName, int page, String categoryFilter, ReportSort sort) {
        openLoadingMenu(viewer);
        reportsManager.loadPlayerReportsAsync(playerId, categoryFilter, sort, reports -> {
            if (!viewer.isOnline()) return;
            renderPlayerMenu(viewer, playerId, playerName, reports, page, categoryFilter, sort);
        });
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof ReportsMenuHolder)) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= topInventory.getSize()) return;

        ReportsMenuHolder holder = (ReportsMenuHolder) topInventory.getHolder();
        Consumer<Player> action = holder.actions.get(event.getRawSlot());
        if (action != null) {
            action.accept((Player) event.getWhoClicked());
        }
    }

    private void renderMainMenu(Player viewer, List<PlayerReportSummary> summaries, int requestedPage, String categoryFilter, ReportSort sort) {
        List<Integer> contentSlots = contentSlots();
        int totalPages = pages(summaries.size(), contentSlots.size());
        int page = clampPage(requestedPage, totalPages);

        ReportsMenuHolder holder = new ReportsMenuHolder(MenuView.MAIN, page, categoryFilter, sort);
        Inventory inventory = createInventory(holder, title("reports.title", placeholders(categoryFilter, sort, page, totalPages)));
        fill(inventory);
        holder.inventory = inventory;

        int start = page * contentSlots.size();
        for (int i = 0; i < contentSlots.size(); i++) {
            int index = start + i;
            if (index >= summaries.size()) break;

            PlayerReportSummary summary = summaries.get(index);
            int slot = contentSlots.get(i);
            inventory.setItem(slot, playerItem(summary, categoryFilter));
            holder.actions.put(slot, player -> openPlayerMenu(player, summary.getPlayerId(), summary.getPlayerName(), 0, categoryFilter, sort));
        }

        if (summaries.isEmpty()) {
            inventory.setItem(slot("reports.items.empty", 22), item("reports.items.empty", placeholders(categoryFilter, sort, page, totalPages), Material.BARRIER));
        }

        addCategoryButtons(inventory, holder);
        addSortButton(inventory, holder, page, totalPages);
        addPageButtons(inventory, holder, page, totalPages, () -> openMainMenu(viewer, page - 1, categoryFilter, sort), () -> openMainMenu(viewer, page + 1, categoryFilter, sort));

        viewer.openInventory(inventory);
    }

    private void renderPlayerMenu(Player viewer, UUID playerId, String playerName, List<ReportEntry> reports, int requestedPage, String categoryFilter, ReportSort sort) {
        List<Integer> contentSlots = contentSlots();
        int totalPages = pages(reports.size(), contentSlots.size());
        int page = clampPage(requestedPage, totalPages);

        Map<String, String> placeholders = placeholders(categoryFilter, sort, page, totalPages);
        placeholders.put("player", playerName);

        ReportsMenuHolder holder = new ReportsMenuHolder(MenuView.PLAYER, page, categoryFilter, sort);
        Inventory inventory = createInventory(holder, title("reports.player-title", placeholders));
        fill(inventory);
        holder.inventory = inventory;

        int start = page * contentSlots.size();
        for (int i = 0; i < contentSlots.size(); i++) {
            int index = start + i;
            if (index >= reports.size()) break;

            ReportEntry report = reports.get(index);
            inventory.setItem(contentSlots.get(i), reportItem(report));
        }

        if (reports.isEmpty()) {
            inventory.setItem(slot("reports.items.empty", 22), item("reports.items.empty", placeholders, Material.BARRIER));
        }

        addCategoryButtons(inventory, holder, player -> openPlayerMenu(player, playerId, playerName, 0, holder.categoryFilter, holder.sort));
        addSortButton(inventory, holder, page, totalPages, player -> openPlayerMenu(player, playerId, playerName, 0, categoryFilter, sort.next()));
        addBackButton(inventory, holder, player -> openMainMenu(player, 0, categoryFilter, sort));
        addPageButtons(inventory, holder, page, totalPages, () -> openPlayerMenu(viewer, playerId, playerName, page - 1, categoryFilter, sort), () -> openPlayerMenu(viewer, playerId, playerName, page + 1, categoryFilter, sort));

        viewer.openInventory(inventory);
    }

    private void openLoadingMenu(Player viewer) {
        ReportsMenuHolder holder = new ReportsMenuHolder(MenuView.LOADING, 0, null, ReportSort.LATEST);
        Inventory inventory = createInventory(holder, title("reports.loading-title", new HashMap<>()));
        holder.inventory = inventory;
        fill(inventory);
        inventory.setItem(slot("reports.items.loading", 22), item("reports.items.loading", new HashMap<>(), Material.CLOCK));
        viewer.openInventory(inventory);
    }

    private void addCategoryButtons(Inventory inventory, ReportsMenuHolder holder) {
        addCategoryButtons(inventory, holder, player -> openMainMenu(player, 0, holder.categoryFilter, holder.sort));
    }

    private void addCategoryButtons(Inventory inventory, ReportsMenuHolder holder, Consumer<Player> refreshAction) {
        int allSlot = slot("reports.items.all-categories", 36);
        inventory.setItem(allSlot, item("reports.items.all-categories", placeholders(holder.categoryFilter, holder.sort, holder.page, 1), Material.BOOK));
        holder.actions.put(allSlot, player -> {
            holder.categoryFilter = null;
            refreshAction.accept(player);
        });

        ConfigurationSection section = menuConfig.getConfigurationSection("reports.category-buttons");
        if (section == null) return;

        for (String category : section.getKeys(false)) {
            String path = "reports.category-buttons." + category;
            int slot = slot(path, -1);
            if (slot < 0 || slot >= inventory.getSize()) continue;

            Map<String, String> placeholders = placeholders(holder.categoryFilter, holder.sort, holder.page, 1);
            placeholders.put("category", category);
            placeholders.put("category_name", categoryName(category));
            placeholders.put("selected", category.equals(holder.categoryFilter) ? selectedText() : "");

            inventory.setItem(slot, item(path, placeholders, Material.PAPER));
            holder.actions.put(slot, player -> {
                holder.categoryFilter = category;
                refreshAction.accept(player);
            });
        }
    }

    private void addSortButton(Inventory inventory, ReportsMenuHolder holder, int page, int totalPages) {
        addSortButton(inventory, holder, page, totalPages, player -> openMainMenu(player, 0, holder.categoryFilter, holder.sort.next()));
    }

    private void addSortButton(Inventory inventory, ReportsMenuHolder holder, int page, int totalPages, Consumer<Player> action) {
        int slot = slot("reports.items.sort", 49);
        Map<String, String> placeholders = placeholders(holder.categoryFilter, holder.sort, page, totalPages);
        inventory.setItem(slot, item("reports.items.sort", placeholders, Material.HOPPER));
        holder.actions.put(slot, action);
    }

    private void addBackButton(Inventory inventory, ReportsMenuHolder holder, Consumer<Player> action) {
        int slot = slot("reports.items.back", 45);
        inventory.setItem(slot, item("reports.items.back", new HashMap<>(), Material.OAK_DOOR));
        holder.actions.put(slot, action);
    }

    private void addPageButtons(Inventory inventory, ReportsMenuHolder holder, int page, int totalPages, Runnable previous, Runnable next) {
        Map<String, String> placeholders = placeholders(holder.categoryFilter, holder.sort, page, totalPages);

        if (page > 0) {
            int previousSlot = slot("reports.items.previous-page", 46);
            inventory.setItem(previousSlot, item("reports.items.previous-page", placeholders, Material.ARROW));
            holder.actions.put(previousSlot, ignored -> previous.run());
        }

        if (page + 1 < totalPages) {
            int nextSlot = slot("reports.items.next-page", 52);
            inventory.setItem(nextSlot, item("reports.items.next-page", placeholders, Material.ARROW));
            holder.actions.put(nextSlot, ignored -> next.run());
        }
    }

    private ItemStack playerItem(PlayerReportSummary summary, String categoryFilter) {
        ReportEntry latest = summary.getLatestReport();
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", summary.getPlayerName());
        placeholders.put("total", String.valueOf(summary.getTotalReports()));
        placeholders.put("latest_message", clip(latest.getMessage()));
        placeholders.put("latest_rule", latest.getRuleId());
        placeholders.put("latest_category", latest.getCategoryId());
        placeholders.put("latest_category_name", categoryName(latest.getCategoryId()));
        placeholders.put("latest_time", formatTime(latest.getTimestamp()));
        placeholders.put("category_counts", categoryCounts(summary.getCategoryCounts()));
        placeholders.put("selected_category", categoryFilter == null ? allCategoriesText() : categoryName(categoryFilter));

        ItemStack item = item("reports.items.player", placeholders, Material.PLAYER_HEAD);
        if (item.getType() == Material.PLAYER_HEAD && menuConfig.getBoolean("reports.use-player-heads", false)) {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof SkullMeta) {
                ((SkullMeta) meta).setOwningPlayer(Bukkit.getOfflinePlayer(summary.getPlayerId()));
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    private ItemStack reportItem(ReportEntry report) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("id", String.valueOf(report.getId()));
        placeholders.put("player", report.getPlayerName());
        placeholders.put("message", clip(report.getMessage()));
        placeholders.put("full_message", report.getMessage());
        placeholders.put("rule", report.getRuleId());
        placeholders.put("category", report.getCategoryId());
        placeholders.put("category_name", categoryName(report.getCategoryId()));
        placeholders.put("time", formatTime(report.getTimestamp()));
        return item("reports.items.report", placeholders, Material.PAPER);
    }

    private Inventory createInventory(ReportsMenuHolder holder, String title) {
        return Bukkit.createInventory(holder, size(), title);
    }

    private void fill(Inventory inventory) {
        if (!menuConfig.getBoolean("reports.items.filler.enabled", true)) return;
        ItemStack filler = item("reports.items.filler", new HashMap<>(), Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
    }

    private ItemStack item(String path, Map<String, String> placeholders, Material defaultMaterial) {
        Material material = material(menuConfig.getString(path + ".material"), defaultMaterial);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String name = menuConfig.getString(path + ".name", "");
        meta.setDisplayName(color(replace(name, placeholders)));

        List<String> lore = new ArrayList<>();
        for (String line : menuConfig.getStringList(path + ".lore")) {
            lore.add(color(replace(line, placeholders)));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private Map<String, String> placeholders(String categoryFilter, ReportSort sort, int page, int totalPages) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("page", String.valueOf(page + 1));
        placeholders.put("pages", String.valueOf(totalPages));
        placeholders.put("sort", sortName(sort));
        placeholders.put("selected_category", categoryFilter == null ? allCategoriesText() : categoryName(categoryFilter));
        return placeholders;
    }

    private String replace(String text, Map<String, String> placeholders) {
        String result = text == null ? "" : text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    private String color(String text) {
        return ColorUtil.translateColorCodes(text);
    }

    private String title(String path, Map<String, String> placeholders) {
        return color(replace(menuConfig.getString(path, "&8Reports"), placeholders));
    }

    private int size() {
        int size = menuConfig.getInt("reports.size", 54);
        if (size < 9) return 9;
        if (size > 54) return 54;
        return size - (size % 9);
    }

    private int slot(String path, int defaultSlot) {
        return menuConfig.getInt(path + ".slot", defaultSlot);
    }

    private List<Integer> contentSlots() {
        List<Integer> slots = menuConfig.getIntegerList("reports.content-slots");
        if (!slots.isEmpty()) return slots;
        return Arrays.asList(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34);
    }

    private int pages(int items, int perPage) {
        if (perPage <= 0) return 1;
        return Math.max(1, (int) Math.ceil(items / (double) perPage));
    }

    private int clampPage(int page, int totalPages) {
        return Math.max(0, Math.min(page, totalPages - 1));
    }

    private Material material(String name, Material fallback) {
        if (name == null || name.isEmpty()) return fallback;
        Material material = Material.matchMaterial(name);
        return material != null ? material : fallback;
    }

    private String categoryName(String category) {
        return menuConfig.getString("reports.category-buttons." + category + ".display", "Класс " + category);
    }

    private String selectedText() {
        return menuConfig.getString("reports.selected-text", "&aвыбрано");
    }

    private String allCategoriesText() {
        return menuConfig.getString("reports.all-categories-text", "Все");
    }

    private String sortName(ReportSort sort) {
        return menuConfig.getString("reports.sort-names." + sort.getId(), sort.getId());
    }

    private String formatTime(long timestamp) {
        String pattern = menuConfig.getString("reports.date-format", "dd.MM.yyyy HH:mm");
        return DateTimeFormatter.ofPattern(pattern)
                .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime());
    }

    private String clip(String message) {
        int max = menuConfig.getInt("reports.message-preview-length", 80);
        if (message == null) return "";
        return message.length() > max ? message.substring(0, max) + "..." : message;
    }

    private String categoryCounts(Map<String, Integer> counts) {
        if (counts.isEmpty()) return "-";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            parts.add(categoryName(entry.getKey()) + ": " + entry.getValue());
        }
        return String.join(", ", parts);
    }

    private void saveDefaultMenu() {
        plugin.getDataFolder().mkdirs();
        if (!menuFile.exists()) {
            plugin.saveResource("menu.yml", false);
        }
    }

    private enum MenuView {
        LOADING,
        MAIN,
        PLAYER
    }

    private static class ReportsMenuHolder implements InventoryHolder {
        private Inventory inventory;
        private final MenuView view;
        private final int page;
        private String categoryFilter;
        private final ReportSort sort;
        private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

        private ReportsMenuHolder(MenuView view, int page, String categoryFilter, ReportSort sort) {
            this.view = view;
            this.page = page;
            this.categoryFilter = categoryFilter;
            this.sort = sort;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
