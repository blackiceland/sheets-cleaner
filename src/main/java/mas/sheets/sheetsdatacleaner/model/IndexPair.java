package mas.sheets.sheetsdatacleaner.model;

public record IndexPair(int first, int second) {

    public static IndexPair of(int a, int b) {
        return a < b ? new IndexPair(a, b) : new IndexPair(b, a);
    }
}
