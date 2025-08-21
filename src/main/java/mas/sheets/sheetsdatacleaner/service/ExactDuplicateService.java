package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.ExactStageResult;

public interface ExactDuplicateService {

    ExactStageResult detectExact(DuplicateMatchRequest request);
} 