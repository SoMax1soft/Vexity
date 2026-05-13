package so.max1soft.vexitychat.managers;

public enum ReportSort {
    LATEST("latest"),
    OLDEST("oldest"),
    MOST_VIOLATIONS("most"),
    NAME("name");

    private final String id;

    ReportSort(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public ReportSort next() {
        ReportSort[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
