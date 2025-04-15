package mas.sheets.sheetsdatacleaner.dto.request;

import java.util.List;

public record DuplicateMatchRequest(
        String range,
        List<String> headers,
        List<List<String>> rows,
        String sheetName,
        String spreadsheetId,
        boolean containsHeader
) {}