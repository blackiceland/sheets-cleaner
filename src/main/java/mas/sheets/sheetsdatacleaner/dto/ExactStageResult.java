package mas.sheets.sheetsdatacleaner.dto;

import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.model.ExactDetectionResult;
import mas.sheets.sheetsdatacleaner.model.RowNorm;

import java.util.List;

/**
 * Результат стадии точного детектора.
 */
public record ExactStageResult(
        DuplicateMatchResponse exactResponse,
        List<RowNorm> normalizedRows,
        ExactDetectionResult exactResult
) {
} 