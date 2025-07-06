package mas.sheets.sheetsdatacleaner.dto.request;

import java.util.List;

public record FuzzyDuplicateRequest(
        String datasetToken,
        List<Long> removedRowIds
) {
} 