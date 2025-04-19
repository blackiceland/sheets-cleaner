package mas.sheets.sheetsdatacleaner.util;

import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Objects;

@UtilityClass
public class RowNormalizer {

    public static List<List<String>> normalizeRows(List<List<String>> rows) {
        return rows.stream()
                .filter(Objects::nonNull)
                .map(RowNormalizer::normalizeRow)
                .toList();
    }

    private static List<String> normalizeRow(List<String> row) {
        return row.stream()
                .map(RowNormalizer::normalizeCell)
                .filter(cell -> !cell.isEmpty())
                .toList();
    }

    private static String normalizeCell(String cell) {
        return cell == null
                ? ""
                : cell.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
