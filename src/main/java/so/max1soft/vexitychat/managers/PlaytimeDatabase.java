package so.max1soft.vexitychat.managers;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class PlaytimeDatabase {

    private final JavaPlugin plugin;
    private final String jdbcUrl;
    private volatile Connection connection;
    private final AtomicBoolean available = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "VexityChat-DB");
        t.setDaemon(true);
        return t;
    });

    public PlaytimeDatabase(JavaPlugin plugin) {
        this.plugin = plugin;
        File dbFile = new File(plugin.getDataFolder(), "playtime");
        this.jdbcUrl = "jdbc:h2:file:" + dbFile.getAbsolutePath() + ";MODE=MySQL;AUTO_SERVER=FALSE";
        connect();
    }

    private void connect() {
        try {
            Class.forName("so.max1soft.vexitychat.libs.h2.Driver");
            Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
            try (Statement st = conn.createStatement()) {
                st.execute(
                    "CREATE TABLE IF NOT EXISTS playtime (" +
                    "  uuid VARCHAR(36) PRIMARY KEY," +
                    "  seconds BIGINT NOT NULL DEFAULT 0" +
                    ")"
                );
            }
            this.connection = conn;
            available.set(true);
            plugin.getLogger().info("[PlaytimeDB] Подключено.");
        } catch (Exception e) {
            available.set(false);
            plugin.getLogger().warning("[PlaytimeDB] Ошибка подключения: " + e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!reconnecting.compareAndSet(false, true)) return;
        scheduler.schedule(() -> {
            reconnecting.set(false);
            plugin.getLogger().info("[PlaytimeDB] Попытка переподключения...");
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
        plugin.getLogger().warning("[PlaytimeDB] Ошибка: " + e.getMessage());
        available.set(false);
        scheduleReconnect();
    }

    /** Возвращает накопленное время в секундах. При недоступной БД возвращает 0. */
    public long getSeconds(UUID uuid) {
        if (!isConnected()) return 0;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT seconds FROM playtime WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            handleError(e);
            return 0;
        }
    }

    /** Добавляет секунды асинхронно — не блокирует основной поток. */
    public void addSeconds(UUID uuid, long seconds) {
        if (seconds <= 0) return;
        scheduler.submit(() -> {
            if (!isConnected()) return;
            try (PreparedStatement ps = connection.prepareStatement(
                    "MERGE INTO playtime (uuid, seconds) KEY(uuid) " +
                    "VALUES (?, COALESCE((SELECT seconds FROM playtime WHERE uuid = ?), 0) + ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, uuid.toString());
                ps.setLong(3, seconds);
                ps.executeUpdate();
            } catch (SQLException e) {
                handleError(e);
            }
        });
    }

    public boolean isAvailable() {
        return isConnected();
    }

    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("[PlaytimeDB] Не все задачи сохранения успели завершиться за 5 секунд.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) {}
    }
}
