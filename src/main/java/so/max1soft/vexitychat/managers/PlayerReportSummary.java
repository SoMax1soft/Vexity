package so.max1soft.vexitychat.managers;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerReportSummary {

    private final UUID playerId;
    private final String playerName;
    private final int totalReports;
    private final ReportEntry latestReport;
    private final Map<String, Integer> categoryCounts;

    public PlayerReportSummary(UUID playerId, String playerName, int totalReports, ReportEntry latestReport, Map<String, Integer> categoryCounts) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.totalReports = totalReports;
        this.latestReport = latestReport;
        this.categoryCounts = Collections.unmodifiableMap(new HashMap<>(categoryCounts));
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public int getTotalReports() {
        return totalReports;
    }

    public ReportEntry getLatestReport() {
        return latestReport;
    }

    public Map<String, Integer> getCategoryCounts() {
        return categoryCounts;
    }
}
