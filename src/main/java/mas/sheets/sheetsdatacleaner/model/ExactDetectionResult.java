package mas.sheets.sheetsdatacleaner.model;

import java.util.List;
import java.util.Map;

public record ExactDetectionResult(
        List<List<Integer>> duplicateGroups,
        List<RowNorm> remainRows,
        List<Integer> remainIdxSrc,
        Map<Integer, RowMeta> metaByIdx
) {}