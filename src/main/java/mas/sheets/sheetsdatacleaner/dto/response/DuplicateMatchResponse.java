package mas.sheets.sheetsdatacleaner.dto.response;

import java.util.List;

public record DuplicateMatchResponse(
        List<String> normalizedRow,
        int originalRowIndex,
        List<Integer> duplicateRowIndexes
) {
}