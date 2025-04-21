package mas.sheets.sheetsdatacleaner.controller;

import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;

import java.util.List;

public interface DuplicateController {

    DuplicateMatchResponse detectDuplicates(DuplicateMatchRequest request);

}
