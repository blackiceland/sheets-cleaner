package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.ExactStageResult;

public interface ExactDuplicateService {

    /**
     * Выполняет нормализацию строк и поиск точных дубликатов.
     *
     * @param request исходные строки (может содержать header).
     * @return результат точного детектора + нормализованные строки.
     */
    ExactStageResult detectExact(DuplicateMatchRequest request);
} 