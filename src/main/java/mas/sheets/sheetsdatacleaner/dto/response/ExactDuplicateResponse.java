package mas.sheets.sheetsdatacleaner.dto.response;

public record ExactDuplicateResponse(
        String datasetToken,
        DuplicateMatchResponse duplicateResponse
) {} 