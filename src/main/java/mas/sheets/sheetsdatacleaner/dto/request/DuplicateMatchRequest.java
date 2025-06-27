package mas.sheets.sheetsdatacleaner.dto.request;

import jakarta.validation.constraints.Size;

import java.util.List;

public record DuplicateMatchRequest(
        @Size(max = 15_000)
        List<List<String>> rows,
        boolean hasHeaders
) {}