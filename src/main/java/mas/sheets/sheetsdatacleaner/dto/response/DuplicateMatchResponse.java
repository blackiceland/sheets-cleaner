package mas.sheets.sheetsdatacleaner.dto.response;

import java.util.List;

public record DuplicateMatchResponse(
        List<String> normalizedRows,
        int originalRowIndex,
        List<Integer> duplicateRowIndexes
) {
}