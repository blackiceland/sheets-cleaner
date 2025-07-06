package mas.sheets.sheetsdatacleaner.dto.response;

import java.util.List;

public record ExactDuplicateResponse(
        String datasetToken,
        DuplicateMatchResponse duplicateResponse,
        List<RowIndexId> rows
) {
    public record RowIndexId(int idx, long rowId) {}
} 