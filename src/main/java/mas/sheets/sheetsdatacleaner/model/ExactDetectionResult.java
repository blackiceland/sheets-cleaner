package mas.sheets.sheetsdatacleaner.model;

import java.util.List;

public record ExactDetectionResult(
        List<List<Integer>> duplicateGroups,
        List<RowNorm> remainingRows,
        List<Integer> originalIndexes
) {
}