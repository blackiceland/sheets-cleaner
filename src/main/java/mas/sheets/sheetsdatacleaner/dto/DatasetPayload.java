package mas.sheets.sheetsdatacleaner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import mas.sheets.sheetsdatacleaner.model.RowNorm;
import mas.sheets.sheetsdatacleaner.model.ExactDetectionResult;

import java.util.List;

/**
 * Содержимое dataset-токена, которое едет между Exact и Fuzzy стадиями.
 *
 * @param exp   Unix-время истечения (секунды).
 * @param rows  нормализованные строки (rowId + value + idx).
 * @param exact результат детектора точных дубликатов.
 */
public record DatasetPayload(
        @JsonProperty("exp") long exp,
        @JsonProperty("rows") List<RowNorm> rows,
        @JsonProperty("exact") ExactDetectionResult exact
) {
} 