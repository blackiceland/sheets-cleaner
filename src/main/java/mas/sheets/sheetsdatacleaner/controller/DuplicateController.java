package mas.sheets.sheetsdatacleaner.controller;

import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.request.FuzzyDuplicateRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.dto.response.ExactDuplicateResponse;

public interface DuplicateController {

    DuplicateMatchResponse detectDuplicates(DuplicateMatchRequest request);

    ExactDuplicateResponse exactStage(DuplicateMatchRequest request);

    DuplicateMatchResponse fuzzyStage(FuzzyDuplicateRequest request);

}
