package so.max1soft.vexitychat.managers;

import java.util.UUID;

public class ReportEntry {

    private final int id;
    private final UUID playerId;
    private final String playerName;
    private final String message;
    private final String categoryId;
    private final String ruleId;
    private final long timestamp;

    public ReportEntry(int id, UUID playerId, String playerName, String message, String categoryId, String ruleId, long timestamp) {
        this.id = id;
        this.playerId = playerId;
        this.playerName = playerName;
        this.message = message;
        this.categoryId = categoryId;
        this.ruleId = ruleId;
        this.timestamp = timestamp;
    }

    public int getId() {
        return id;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getMessage() {
        return message;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public String getRuleId() {
        return ruleId;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
