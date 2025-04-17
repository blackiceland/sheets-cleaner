package mas.sheets.sheetsdatacleaner.controller;

import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;

import java.util.List;

public interface DuplicateController {

    List<DuplicateMatchResponse> detectDuplicates(DuplicateMatchRequest request);

}
