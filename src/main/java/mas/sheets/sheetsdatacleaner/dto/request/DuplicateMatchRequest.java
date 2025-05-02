package mas.sheets.sheetsdatacleaner.dto.request;

import java.util.List;

public record DuplicateMatchRequest(
        List<List<String>> rows
) {}