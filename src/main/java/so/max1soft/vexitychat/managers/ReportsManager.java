package so.max1soft.vexitychat.managers;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class ReportsManager {

    private final JavaPlugin plugin;
    private final String jdbcUrl;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final AtomicBoolean available = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "VexityChat-ReportsDB");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Connection connection;

    public ReportsManager(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.getDataFolder().mkdirs();
        File dbFile = new File(plugin.getDataFolder(), "reports");
        this.jdbcUrl = "jdbc:h2:file:" + dbFile.getAbsolutePath() + ";MODE=MySQL;AUTO_SERVER=FALSE";
        connect();
        migrateYamlIfNeeded(new File(plugin.getDataFolder(), "reports.yml"));
        nextId.set(loadNextId());
    }

    public void recordViolation(Player player, String message, String categoryId, String ruleId) {
        if (player == null || message == null) return;

        ReportEntry entry = new ReportEntry(
                nextId.getAndIncrement(),
                player.getUniqueId(),
                player.getName(),
                trimMessage(message),
                categoryId,
                ruleId,
                System.currentTimeMillis());

        scheduler.execute(() -> {
            if (!isConnected()) {
                plugin.getLogger().warning("[ReportsDB] БД недоступна, репорт не сохранён.");
                scheduleReconnect();
                return;
            }

            try {
                insertReport(entry);
            } catch (SQLException e) {
                handleError(e);
            }
        });
    }

    public void loadSummariesAsync(String categoryFilter, ReportSort sort, Consumer<List<PlayerReportSummary>> callback) {
        scheduler.execute(() -> {
            List<PlayerReportSummary> summaries = Collections.emptyList();
            if (isConnected()) {
                try {
                    summaries = fetchSummaries(categoryFilter, sort);
                } catch (SQLException e) {
                    handleError(e);
                }
            }
            completeOnMain(callback, summaries);
        });
    }

    public void loadPlayerReportsAsync(UUID playerId, String categoryFilter, ReportSort sort, Consumer<List<ReportEntry>> callback) {
        scheduler.execute(() -> {
            List<ReportEntry> reports = Collections.emptyList();
            if (isConnected()) {
                try {
                    reports = fetchPlayerReports(playerId, categoryFilter, sort);
                } catch (SQLException e) {
                    handleError(e);
                }
            }
            completeOnMain(callback, reports);
        });
    }

    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("[ReportsDB] Не все задачи сохранения успели завершиться за 5 секунд.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
        }
    }

    private synchronized void connect() {
        try {
            Class.forName("so.max1soft.vexitychat.libs.h2.Driver");
            Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
            try (Statement st = conn.createStatement()) {
                st.execute(
                        "CREATE TABLE IF NOT EXISTS reports (" +
                                "id INT NOT NULL PRIMARY KEY," +
                                "uuid VARCHAR(36) NOT NULL," +
                                "player_name VARCHAR(64) NOT NULL," +
                                "message VARCHAR(512) NOT NULL," +
                                "category VARCHAR(32) NOT NULL," +
                                "rule_id VARCHAR(64) NOT NULL," +
                                "created_at BIGINT NOT NULL" +
                                ")"
                );
                st.execute("CREATE INDEX IF NOT EXISTS idx_reports_uuid ON reports(uuid)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_reports_category ON reports(category)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_reports_created ON reports(created_at)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_reports_uuid_category ON reports(uuid, category)");
            }
            this.connection = conn;
            available.set(true);
            plugin.getLogger().info("[ReportsDB] Подключено.");
        } catch (Exception e) {
            available.set(false);
            plugin.getLogger().warning("[ReportsDB] Ошибка подключения: " + e.getMessage());
            scheduleReconnect();
        }
    }

    private void insertReport(ReportEntry entry) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO reports (id, uuid, player_name, message, category, rule_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, entry.getId());
            ps.setString(2, entry.getPlayerId().toString());
            ps.setString(3, entry.getPlayerName());
            ps.setString(4, entry.getMessage());
            ps.setString(5, entry.getCategoryId());
            ps.setString(6, entry.getRuleId());
            ps.setLong(7, entry.getTimestamp());
            ps.executeUpdate();
        }
    }

    private List<PlayerReportSummary> fetchSummaries(String categoryFilter, ReportSort sort) throws SQLException {
        Map<UUID, Map<String, Integer>> categoryCounts = fetchCategoryCounts(categoryFilter);
        boolean filtered = hasCategoryFilter(categoryFilter);
        String where = filtered ? " WHERE category = ?" : "";
        String sql =
                "SELECT r.id, r.uuid, r.player_name, r.message, r.category, r.rule_id, r.created_at, totals.total_reports " +
                        "FROM reports r " +
                        "JOIN (" +
                        "  SELECT uuid, MAX(id) AS latest_id, COUNT(*) AS total_reports " +
                        "  FROM reports" + where +
                        "  GROUP BY uuid" +
                        ") totals ON r.id = totals.latest_id " +
                        summaryOrder(sort);

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (filtered) {
                ps.setString(1, categoryFilter);
            }

            List<PlayerReportSummary> summaries = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ReportEntry latest = entryFromResultSet(rs);
                    int total = rs.getInt("total_reports");
                    summaries.add(new PlayerReportSummary(
                            latest.getPlayerId(),
                            latest.getPlayerName(),
                            total,
                            latest,
                            categoryCounts.getOrDefault(latest.getPlayerId(), Collections.emptyMap())));
                }
            }
            return summaries;
        }
    }

    private Map<UUID, Map<String, Integer>> fetchCategoryCounts(String categoryFilter) throws SQLException {
        boolean filtered = hasCategoryFilter(categoryFilter);
        String sql = "SELECT uuid, category, COUNT(*) AS amount FROM reports" +
                (filtered ? " WHERE category = ?" : "") +
                " GROUP BY uuid, category";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (filtered) {
                ps.setString(1, categoryFilter);
            }

            Map<UUID, Map<String, Integer>> counts = new HashMap<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    counts.computeIfAbsent(uuid, ignored -> new HashMap<>())
                            .put(rs.getString("category"), rs.getInt("amount"));
                }
            }
            return counts;
        }
    }

    private List<ReportEntry> fetchPlayerReports(UUID playerId, String categoryFilter, ReportSort sort) throws SQLException {
        boolean filtered = hasCategoryFilter(categoryFilter);
        String sql = "SELECT id, uuid, player_name, message, category, rule_id, created_at FROM reports WHERE uuid = ?" +
                (filtered ? " AND category = ?" : "") +
                reportOrder(sort);

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            if (filtered) {
                ps.setString(2, categoryFilter);
            }

            List<ReportEntry> reports = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    reports.add(entryFromResultSet(rs));
                }
            }
            return reports;
        }
    }

    private ReportEntry entryFromResultSet(ResultSet rs) throws SQLException {
        return new ReportEntry(
                rs.getInt("id"),
                UUID.fromString(rs.getString("uuid")),
                rs.getString("player_name"),
                rs.getString("message"),
                rs.getString("category"),
                rs.getString("rule_id"),
                rs.getLong("created_at"));
    }

    private String summaryOrder(ReportSort sort) {
        if (sort == ReportSort.OLDEST) {
            return " ORDER BY r.created_at ASC, r.id ASC";
        }
        if (sort == ReportSort.MOST_VIOLATIONS) {
            return " ORDER BY totals.total_reports DESC, LOWER(r.player_name) ASC";
        }
        if (sort == ReportSort.NAME) {
            return " ORDER BY LOWER(r.player_name) ASC";
        }
        return " ORDER BY r.created_at DESC, r.id DESC";
    }

    private String reportOrder(ReportSort sort) {
        if (sort == ReportSort.OLDEST) {
            return " ORDER BY created_at ASC, id ASC";
        }
        if (sort == ReportSort.MOST_VIOLATIONS) {
            return " ORDER BY category ASC, created_at DESC, id DESC";
        }
        if (sort == ReportSort.NAME) {
            return " ORDER BY LOWER(rule_id) ASC, created_at DESC, id DESC";
        }
        return " ORDER BY created_at DESC, id DESC";
    }

    private void migrateYamlIfNeeded(File reportsFile) {
        if (!reportsFile.exists() || !isConnected() || hasRows()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(reportsFile);
        ConfigurationSection section = yaml.getConfigurationSection("reports");
        if (section == null) return;

        int migrated = 0;
        for (String key : section.getKeys(false)) {
            try {
                int id = Integer.parseInt(key);
                String path = "reports." + key;
                ReportEntry entry = new ReportEntry(
                        id,
                        UUID.fromString(yaml.getString(path + ".uuid", "")),
                        yaml.getString(path + ".name", "Unknown"),
                        trimMessage(yaml.getString(path + ".message", "")),
                        yaml.getString(path + ".category", "?"),
                        yaml.getString(path + ".rule", yaml.getString(path + ".category", "?")),
                        yaml.getLong(path + ".time", 0L));
                insertReport(entry);
                migrated++;
            } catch (Exception e) {
                plugin.getLogger().warning("[ReportsDB] Пропущен сломанный report '" + key + "': " + e.getMessage());
            }
        }

        if (migrated > 0) {
            plugin.getLogger().info("[ReportsDB] Импортировано репортов из reports.yml: " + migrated);
        }
    }

    private int loadNextId() {
        if (!isConnected()) return 1;

        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(id), 0) + 1 FROM reports")) {
            return rs.next() ? Math.max(1, rs.getInt(1)) : 1;
        } catch (SQLException e) {
            handleError(e);
            return 1;
        }
    }

    private boolean hasRows() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM reports")) {
            return rs.next() && rs.getLong(1) > 0;
        } catch (SQLException e) {
            handleError(e);
            return true;
        }
    }

    private <T> void completeOnMain(Consumer<List<T>> callback, List<T> result) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
    }

    private void scheduleReconnect() {
        if (!reconnecting.compareAndSet(false, true)) return;
        scheduler.schedule(() -> {
            reconnecting.set(false);
            plugin.getLogger().info("[ReportsDB] Попытка переподключения...");
            connect();
            nextId.set(loadNextId());
        }, 10, TimeUnit.SECONDS);
    }

    private boolean isConnected() {
        try {
            return available.get() && connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    private void handleError(Exception e) {
        plugin.getLogger().warning("[ReportsDB] Ошибка: " + e.getMessage());
        available.set(false);
        scheduleReconnect();
    }

    private boolean hasCategoryFilter(String categoryFilter) {
        return categoryFilter != null && !categoryFilter.isEmpty();
    }

    private String trimMessage(String message) {
        if (message == null) return "";
        int maxLength = 512;
        return message.length() > maxLength ? message.substring(0, maxLength) : message;
    }
}
