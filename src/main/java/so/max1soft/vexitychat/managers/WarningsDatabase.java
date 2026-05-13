package so.max1soft.vexitychat.managers;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class WarningsDatabase {

    private final JavaPlugin plugin;
    private final String jdbcUrl;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "VexityChat-WarningsDB");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean available = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    private volatile Connection connection;

    public WarningsDatabase(JavaPlugin plugin) {
        this.plugin = plugin;
        File dbFile = new File(plugin.getDataFolder(), "warnings");
        this.jdbcUrl = "jdbc:h2:file:" + dbFile.getAbsolutePath() + ";MODE=MySQL;AUTO_SERVER=FALSE";
        connect();
        migrateYamlIfNeeded(new File(plugin.getDataFolder(), "warnings.yml"));
    }

    public synchronized WarningProgress registerWarning(UUID uuid, String categoryId, long expireMillis, int maxWarnings) {
        long now = System.currentTimeMillis();
        int count = 0;
        long lastViolation = 0L;

        if (isConnected()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT count, last_violation FROM warnings WHERE uuid = ? AND category = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, categoryId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        count = rs.getInt("count");
                        lastViolation = rs.getLong("last_violation");
                    }
                }
            } catch (SQLException e) {
                handleError(e);
            }
        }

        if (now - lastViolation > expireMillis) {
            count = 0;
        }

        count++;
        boolean finalWarning = count >= maxWarnings;
        int storedCount = finalWarning ? 0 : count;

        if (isConnected()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "MERGE INTO warnings (uuid, category, count, last_violation) KEY(uuid, category) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, categoryId);
                ps.setInt(3, storedCount);
                ps.setLong(4, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                handleError(e);
            }
        }

        return new WarningProgress(count, maxWarnings, finalWarning);
    }

    public CompletableFuture<WarningProgress> registerWarningAsync(UUID uuid, String categoryId, long expireMillis, int maxWarnings) {
        return CompletableFuture.supplyAsync(() -> registerWarning(uuid, categoryId, expireMillis, maxWarnings), scheduler);
    }

    public boolean isAvailable() {
        return isConnected();
    }

    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("[WarningsDB] Не все задачи сохранения успели завершиться за 5 секунд.");
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
                        "CREATE TABLE IF NOT EXISTS warnings (" +
                                "uuid VARCHAR(36) NOT NULL," +
                                "category VARCHAR(32) NOT NULL," +
                                "count INT NOT NULL DEFAULT 0," +
                                "last_violation BIGINT NOT NULL DEFAULT 0," +
                                "PRIMARY KEY (uuid, category)" +
                                ")"
                );
            }
            this.connection = conn;
            available.set(true);
            plugin.getLogger().info("[WarningsDB] Подключено.");
        } catch (Exception e) {
            available.set(false);
            plugin.getLogger().warning("[WarningsDB] Ошибка подключения: " + e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!reconnecting.compareAndSet(false, true)) return;
        scheduler.schedule(() -> {
            reconnecting.set(false);
            plugin.getLogger().info("[WarningsDB] Попытка переподключения...");
            connect();
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
        plugin.getLogger().warning("[WarningsDB] Ошибка: " + e.getMessage());
        available.set(false);
        scheduleReconnect();
    }

    private void migrateYamlIfNeeded(File warningsFile) {
        if (!warningsFile.exists() || !isConnected() || hasRows()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(warningsFile);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) return;

        int migrated = 0;
        synchronized (this) {
            for (String uuid : players.getKeys(false)) {
                ConfigurationSection categories = players.getConfigurationSection(uuid);
                if (categories == null) continue;

                for (String category : categories.getKeys(false)) {
                    int count = categories.getInt(category + ".count", 0);
                    long last = categories.getLong(category + ".last", 0L);
                    if (count <= 0 && last <= 0L) continue;

                    try (PreparedStatement ps = connection.prepareStatement(
                            "MERGE INTO warnings (uuid, category, count, last_violation) KEY(uuid, category) VALUES (?, ?, ?, ?)")) {
                        ps.setString(1, uuid);
                        ps.setString(2, category);
                        ps.setInt(3, count);
                        ps.setLong(4, last);
                        ps.executeUpdate();
                        migrated++;
                    } catch (SQLException e) {
                        handleError(e);
                        return;
                    }
                }
            }
        }

        if (migrated > 0) {
            plugin.getLogger().info("[WarningsDB] Импортировано варнов из warnings.yml: " + migrated);
        }
    }

    private boolean hasRows() {
        synchronized (this) {
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM warnings")) {
                return rs.next() && rs.getLong(1) > 0;
            } catch (SQLException e) {
                handleError(e);
                return true;
            }
        }
    }

    public static class WarningProgress {
        private final int count;
        private final int maxWarnings;
        private final boolean finalWarning;

        public WarningProgress(int count, int maxWarnings, boolean finalWarning) {
            this.count = count;
            this.maxWarnings = maxWarnings;
            this.finalWarning = finalWarning;
        }

        public int getCount() {
            return count;
        }

        public int getMaxWarnings() {
            return maxWarnings;
        }

        public boolean isFinalWarning() {
            return finalWarning;
        }
    }
}
